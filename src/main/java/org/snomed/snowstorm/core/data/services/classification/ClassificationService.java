package org.snomed.snowstorm.core.data.services.classification;

import com.google.common.collect.Lists;
import io.kaicode.elasticvc.api.BranchService;
import io.kaicode.elasticvc.domain.Branch;
import io.kaicode.elasticvc.domain.Commit;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.ihtsdo.otf.snomedboot.domain.rf2.RelationshipFieldIndexes;
import org.ihtsdo.sso.integration.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.config.Config;
import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.domain.Relationship;
import org.snomed.snowstorm.core.data.domain.classification.*;
import org.snomed.snowstorm.core.data.repositories.ClassificationRepository;
import org.snomed.snowstorm.core.data.repositories.classification.EquivalentConceptsRepository;
import org.snomed.snowstorm.core.data.repositories.classification.RelationshipChangeRepository;
import org.snomed.snowstorm.core.data.services.*;
import org.snomed.snowstorm.core.data.services.classification.pojo.ClassificationStatusResponse;
import org.snomed.snowstorm.core.data.services.classification.pojo.EquivalentConceptsResponse;
import org.snomed.snowstorm.core.rf2.RF2Type;
import org.snomed.snowstorm.core.rf2.export.ExportException;
import org.snomed.snowstorm.core.rf2.export.ExportService;
import org.snomed.snowstorm.core.util.DateUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.GetQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.util.CloseableIterator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import javax.annotation.PostConstruct;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.kaicode.elasticvc.api.ComponentService.LARGE_PAGE;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.index.query.QueryBuilders.termsQuery;
import static org.snomed.snowstorm.core.data.domain.classification.ClassificationStatus.*;

@Service
public class ClassificationService {

	@Value("${classification-service.job.abort-after-minutes}")
	private int abortRemoteClassificationAfterMinutes;

	@Autowired
	private ElasticsearchOperations elasticsearchOperations;

	@Autowired
	private BranchService branchService;

	@Autowired
	private ClassificationRepository classificationRepository;

	@Autowired
	private RelationshipChangeRepository relationshipChangeRepository;

	@Autowired
	private EquivalentConceptsRepository equivalentConceptsRepository;

	@Autowired
	private RemoteClassificationServiceClient serviceClient;

	@Autowired
	private ExportService exportService;

	@Autowired
	private DescriptionService descriptionService;

	@Autowired
	private ConceptService conceptService;

	private final List<Classification> classificationsInProgress;

	private Thread classificationStatusPollingThread;

	private static final int SECOND = 1000;

	private static final PageRequest PAGE_FIRST_1K = PageRequest.of(0, 1000);

	private Logger logger = LoggerFactory.getLogger(getClass());
	private static final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("ddMMyyyy");

	public ClassificationService() {
		classificationsInProgress = new ArrayList<>();
	}

	@PostConstruct
	private void init() {
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withQuery(termsQuery(Classification.Fields.STATUS, ClassificationStatus.SCHEDULED.name(), ClassificationStatus.RUNNING.name()))
				.withPageable(PAGE_FIRST_1K);

		// Mark running classifications as failed. This could be improved in the future.
		final long[] failedCount = {0};
		try (CloseableIterator<Classification> runningClassifications = elasticsearchOperations.stream(queryBuilder.build(), Classification.class)) {
			runningClassifications.forEachRemaining(classification -> {
				classification.setStatus(ClassificationStatus.FAILED);
				classification.setErrorMessage("Termserver restarted.");
				classificationRepository.save(classification);
				failedCount[0]++;
			});
		}
		if (failedCount[0] > 0) {
			logger.info("{} currently running classifications marked as failed due to restart.", failedCount[0]);
		}

		// Start thread to continuously fetch the status of remote classifications
		// Copy the in-progress list to avoid long synchronized block
		classificationStatusPollingThread = new Thread(() -> {
			List<Classification> classificationsToCheck = new ArrayList<>();
			try {
				while (true) {
					try {
						// Copy the in-progress list to avoid long synchronized block
						synchronized (classificationsInProgress) {
							classificationsToCheck.addAll(classificationsInProgress);
						}
						Date remoteClassificationCutoffTime = DateUtil.newDatePlus(Calendar.MINUTE, -abortRemoteClassificationAfterMinutes);
						for (Classification classification : classificationsToCheck) {
							ClassificationStatusResponse statusResponse = serviceClient.getStatus(classification.getId());
							ClassificationStatus latestStatus = statusResponse.getStatus();
							if (latestStatus == ClassificationStatus.FAILED) {
								classification.setErrorMessage(statusResponse.getErrorMessage());
								logger.warn("Remote classification failed with message:{}, developerMessage:{}",
										statusResponse.getErrorMessage(), statusResponse.getDeveloperMessage());
							}
							else if (classification.getCreationDate().before(remoteClassificationCutoffTime)) {
								latestStatus = ClassificationStatus.FAILED;
								classification.setErrorMessage("Remote service taking too long.");
							}
							if (classification.getStatus() != latestStatus) {

								Boolean inferredRelationshipChangesFound = null;
								Boolean equivalentConceptsFound = null;
								if (latestStatus == COMPLETED) {
									try {
										downloadRemoteResults(classification.getId());

										inferredRelationshipChangesFound = doGetRelationshipChanges(classification.getPath(), classification.getId(),
												Config.DEFAULT_LANGUAGE_CODES, PageRequest.of(0, 1), false, null).getTotalElements() > 0;

										equivalentConceptsFound = doGetEquivalentConcepts(classification.getPath(), classification.getId(),
												Config.DEFAULT_LANGUAGE_CODES, PageRequest.of(0, 1)).getTotalElements() > 0;

									} catch (IOException | ElasticsearchException e) {
										latestStatus = ClassificationStatus.FAILED;
										String message = "Failed to capture remote classification results.";
										classification.setErrorMessage(message);
										logger.error(message, e);
									}
								}

								classification.setInferredRelationshipChangesFound(inferredRelationshipChangesFound);
								classification.setEquivalentConceptsFound(equivalentConceptsFound);
								classification.setStatus(latestStatus);
								classification.setCompletionDate(new Date());
								classificationRepository.save(classification);
							}
							if (latestStatus != ClassificationStatus.SCHEDULED && latestStatus != ClassificationStatus.RUNNING) {
								synchronized (classificationsInProgress) {
									classificationsInProgress.remove(classification);
								}
							}
						}
						classificationsToCheck.clear();
						Thread.sleep(SECOND);

					} catch (HttpClientErrorException e) {
						int coolOffSeconds = 30;
						logger.warn("Problem with classification-service communication. Trying again in {} seconds.", coolOffSeconds, e);
						// Let's wait a while before trying again
						Thread.sleep(coolOffSeconds * SECOND);
					}
				}
			} catch (InterruptedException e) {
				logger.info("Classification status polling thread interrupted.");
			} finally {
				logger.info("Classification status polling thread stopped.");
			}
		});
		classificationStatusPollingThread.setName("classification-status-polling");
		classificationStatusPollingThread.start();
	}

	public Page<Classification> findClassifications(String path) {
		NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
				.withQuery(termQuery(Classification.Fields.PATH, path))
				.withSort(SortBuilders.fieldSort(Classification.Fields.CREATION_DATE).order(SortOrder.ASC))
				.withPageable(PAGE_FIRST_1K);
		return elasticsearchOperations.queryForPage(queryBuilder.build(), Classification.class);
	}

	public Classification findClassification(String path, String classificationId) {
		GetQuery getQuery = new GetQuery();
		getQuery.setId(classificationId);
		Classification classification = elasticsearchOperations.queryForObject(getQuery, Classification.class);
		if (classification == null || !path.equals(classification.getPath())) {
			throw new NotFoundException("Classification not found on branch.");
		}
		return path.equals(classification.getPath()) ? classification : null;
	}

	public Classification createClassification(String path, String reasonerId) throws ServiceException {
		Branch branch = branchService.findBranchOrThrow(path);

		Classification classification = new Classification();
		classification.setPath(path);
		classification.setReasonerId(reasonerId);
		classification.setUserId(SecurityUtil.getUsername());
		classification.setCreationDate(new Date());
		classification.setLastCommitDate(branch.getHead());

		Branch branchWithInheritedMetadata = branchService.findBranchOrThrow(path, true);
		Map<String, String> metadata = branchWithInheritedMetadata.getMetadata();
		String previousPackage = metadata != null ? metadata.get(BranchMetadataKeys.CLASSIFICATION_PREVIOUS_PACKAGE) : null;
		if (Strings.isNullOrEmpty(previousPackage)) {
			throw new IllegalStateException("Missing branch metadata for " + BranchMetadataKeys.CLASSIFICATION_PREVIOUS_PACKAGE);
		}

		try {
			File deltaExport = exportService.exportRF2ArchiveFile(path, SIMPLE_DATE_FORMAT.format(new Date()), RF2Type.DELTA, true);
			String remoteClassificationId = serviceClient.createClassification(previousPackage, deltaExport, path, reasonerId);
			classification.setId(remoteClassificationId);
			classification.setStatus(ClassificationStatus.SCHEDULED);
			classificationRepository.save(classification);
			synchronized (classificationsInProgress) {
				classificationsInProgress.add(classification);
			}
		} catch (RestClientException | ExportException e) {
			throw new ServiceException("Failed to create classification.", e);
		}

		return classification;
	}

	@Async
	public void saveClassificationResultsToBranch(String path, String classificationId, SecurityContext securityContext) {
		SecurityContextHolder.setContext(securityContext);
		Classification classification = classificationSaveStatusCheck(path, classificationId);

		if (classification.getInferredRelationshipChangesFound()) {
			classification.setStatus(SAVING_IN_PROGRESS);
			classificationRepository.save(classification);

			try (Commit commit = branchService.openCommit(path)) { // Commit in auto-close try block like this will roll back if an exception is thrown

				NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
						.withQuery(termQuery("classificationId", classificationId))
						.withSort(new FieldSortBuilder("sourceId"))
						.withPageable(LARGE_PAGE);
				try (CloseableIterator<RelationshipChange> relationshipChangeStream = elasticsearchOperations.stream(queryBuilder.build(), RelationshipChange.class)) {
					while (relationshipChangeStream.hasNext()) {
						List<RelationshipChange> changesBatch = new ArrayList<>();
						int i = 0;
						while (i++ < 10_000 && relationshipChangeStream.hasNext()) {
							changesBatch.add(relationshipChangeStream.next());
						}

						// Group changes by concept
						Map<Long, List<RelationshipChange>> conceptToChangeMap = new Long2ObjectOpenHashMap<>();
						for (RelationshipChange relationshipChange : changesBatch) {
							conceptToChangeMap.computeIfAbsent(Long.parseLong(relationshipChange.getSourceId()), conceptId -> new ArrayList<>()).add(relationshipChange);
						}

						// Load concepts
						Collection<Concept> concepts = conceptService.find(path, conceptToChangeMap.keySet(), Config.DEFAULT_LANGUAGE_CODES);

						// Apply changes to concepts
						for (Concept concept : concepts) {
							List<RelationshipChange> relationshipChanges = conceptToChangeMap.get(concept.getConceptIdAsLong());
							applyRelationshipChangesToConcept(concept, relationshipChanges, false);
						}

						// Update concepts
						conceptService.updateWithinCommit(concepts, commit);
					}
				}

				commit.markSuccessful();
				classification.setStatus(SAVED);
				classification.setSaveDate(new Date());
			} catch (ServiceException e) {
				classification.setStatus(SAVE_FAILED);
				logger.error("Classification save failed {} {}", classification.getPath(), classificationId, e);
			}
		} else {
			classification.setStatus(SAVED);
		}

		classificationRepository.save(classification);
	}

	public Classification classificationSaveStatusCheck(String path, String classificationId) {
		Classification classification = findClassification(path, classificationId);
		if (classification.getStatus() != COMPLETED) {
			throw new IllegalStateException("Classification status must be " + COMPLETED.toString() + " in order to save results.");
		}
		return classification;
	}

	private void applyRelationshipChangesToConcept(Concept concept, List<RelationshipChange> relationshipChanges, boolean copyDescriptions) {
		for (RelationshipChange relationshipChange : relationshipChanges) {
			if (relationshipChange.getChangeNature() == ChangeNature.INFERRED) {
				Relationship relationship = new Relationship(
						null,
						null,
						true,
						concept.getModuleId(),
						null,
						relationshipChange.getDestinationId(),
						relationshipChange.getGroup(),
						relationshipChange.getTypeId(),
						relationshipChange.getCharacteristicTypeId(),
						relationshipChange.getModifierId());

				if (copyDescriptions) {
					relationship.setSource(relationshipChange.getSource());
					relationship.setType(relationshipChange.getType());
					relationship.setTarget(relationshipChange.getDestination());
				}

				concept.addRelationship(relationship);
			} else {
				concept.getRelationships().remove(new Relationship(relationshipChange.getRelationshipId()));
			}
		}
	}

	public Page<RelationshipChange> getRelationshipChanges(String path, String classificationId, List<String> languageCodes, PageRequest pageRequest) {
		checkClassificationHasResults(path, classificationId);
		return doGetRelationshipChanges(path, classificationId, languageCodes, pageRequest, true, null);
	}

	private Page<RelationshipChange> doGetRelationshipChanges(String path, String classificationId, List<String> languageCodes, PageRequest pageRequest, boolean fetchDescriptions, String sourceIdFilter) {

		Page<RelationshipChange> relationshipChanges =
				sourceIdFilter != null ?
						relationshipChangeRepository.findByClassificationIdAndSourceId(classificationId, sourceIdFilter, pageRequest)
						: relationshipChangeRepository.findByClassificationId(classificationId, pageRequest);

		Map<String, ConceptMini> conceptMiniMap = new HashMap<>();
		for (RelationshipChange relationshipChange : relationshipChanges) {
			if (fetchDescriptions) {
				relationshipChange.setSource(conceptMiniMap.computeIfAbsent(relationshipChange.getSourceId(), conceptId -> new ConceptMini(conceptId, languageCodes)));
				relationshipChange.setDestination(conceptMiniMap.computeIfAbsent(relationshipChange.getDestinationId(), conceptId -> new ConceptMini(conceptId, languageCodes)));
				relationshipChange.setType(conceptMiniMap.computeIfAbsent(relationshipChange.getTypeId(), conceptId -> new ConceptMini(conceptId, languageCodes)));
			}
		}
		if (fetchDescriptions) {
			descriptionService.joinActiveDescriptions(path, conceptMiniMap);
		}

		return relationshipChanges;
	}

	public Page<EquivalentConceptsResponse> getEquivalentConcepts(String path, String classificationId, List<String> languageCodes, PageRequest pageRequest) {
		checkClassificationHasResults(path, classificationId);
		return doGetEquivalentConcepts(path, classificationId, languageCodes, pageRequest);
	}

	public Concept getConceptPreview(String path, String classificationId, String conceptId, List<String> languageCodes) {
		checkClassificationHasResults(path, classificationId);

		Concept concept = conceptService.find(conceptId, languageCodes, path);
		Page<RelationshipChange> conceptRelationshipChanges = doGetRelationshipChanges(path, classificationId, languageCodes, LARGE_PAGE, true, conceptId);
		applyRelationshipChangesToConcept(concept, conceptRelationshipChanges.getContent(), true);

		return concept;
	}

	private Page<EquivalentConceptsResponse> doGetEquivalentConcepts(String path, String classificationId, List<String> languageCodes, PageRequest pageRequest) {
		Page<EquivalentConcepts> relationshipChanges = equivalentConceptsRepository.findByClassificationId(classificationId, pageRequest);
		if (relationshipChanges.getTotalElements() == 0) {
			return new PageImpl<>(Collections.emptyList());
		}

		Map<String, ConceptMini> conceptMiniMap = new HashMap<>();
		for (EquivalentConcepts equivalentConcepts : relationshipChanges.getContent()) {
			for (String conceptId : equivalentConcepts.getConceptIds()) {
				conceptMiniMap.put(conceptId, new ConceptMini(conceptId, languageCodes));
			}
		}
		descriptionService.joinActiveDescriptions(path, conceptMiniMap);

		List<EquivalentConceptsResponse> responseContent = new ArrayList<>();
		for (EquivalentConcepts equivalentConcepts : relationshipChanges.getContent()) {
			HashSet<EquivalentConceptsResponse.ConceptIdAndLabel> labels = new HashSet<>();
			responseContent.add(new EquivalentConceptsResponse(labels));
			for (String conceptId : equivalentConcepts.getConceptIds()) {
				conceptMiniMap.put(conceptId, new ConceptMini(conceptId, languageCodes));
				labels.add(new EquivalentConceptsResponse.ConceptIdAndLabel(conceptId, conceptMiniMap.get(conceptId).getFsn()));
			}
		}

		return new PageImpl<>(responseContent, pageRequest, relationshipChanges.getTotalElements());
	}

	private void checkClassificationHasResults(String path, String classificationId) {
		Classification classification = findClassification(path, classificationId);
		if (!classification.getStatus().isResultsAvailable()) {
			throw new IllegalStateException("This classification has no results yet.");
		}
	}

	private void downloadRemoteResults(String classificationId) throws IOException, ElasticsearchException {
		logger.info("Downloading remote classification results for {}", classificationId);
		try (ZipInputStream rf2ResultsZipStream = new ZipInputStream(serviceClient.downloadRf2Results(classificationId))) {
			ZipEntry zipEntry;
			while ((zipEntry = rf2ResultsZipStream.getNextEntry()) != null) {
				if (zipEntry.getName().contains("sct2_Relationship_Delta")) {
					saveRelationshipChanges(classificationId, rf2ResultsZipStream);
				}
				if (zipEntry.getName().contains("der2_sRefset_EquivalentConceptSimpleMapDelta")) {
					saveEquivalentConcepts(classificationId, rf2ResultsZipStream);
				}
			}
		}
	}

	private void saveRelationshipChanges(String classificationId, InputStream rf2Stream) throws IOException, ElasticsearchException {
		// Leave the stream open after use.
		BufferedReader reader = new BufferedReader(new InputStreamReader(rf2Stream));

		@SuppressWarnings("UnusedAssignment")
		String line = reader.readLine(); // Read and discard header line

		List<RelationshipChange> relationshipChanges = new ArrayList<>();
		while ((line = reader.readLine()) != null) {
			String[] values = line.split("\\t");
			// Header id	effectiveTime	active	moduleId	sourceId	destinationId	relationshipGroup	typeId	characteristicTypeId	modifierId
			relationshipChanges.add(new RelationshipChange(
					classificationId,
					values[RelationshipFieldIndexes.id],
					"1".equals(values[RelationshipFieldIndexes.active]),
					values[RelationshipFieldIndexes.sourceId],
					values[RelationshipFieldIndexes.destinationId],
					Integer.parseInt(values[RelationshipFieldIndexes.relationshipGroup]),
					values[RelationshipFieldIndexes.typeId],
					values[RelationshipFieldIndexes.modifierId]));
		}
		if (!relationshipChanges.isEmpty()) {
			logger.info("Saving {} classification relationship changes", relationshipChanges.size());
			List<List<RelationshipChange>> partition = Lists.partition(relationshipChanges, 10_000);
			for (List<RelationshipChange> changes : partition) {
				relationshipChangeRepository.saveAll(changes);
			}
		}
	}

	private void saveEquivalentConcepts(String classificationId, InputStream rf2Stream) throws IOException, ElasticsearchException {
		// Leave the stream open after use.
		BufferedReader reader = new BufferedReader(new InputStreamReader(rf2Stream));

		@SuppressWarnings("UnusedAssignment")
		String line = reader.readLine(); // Read and discard header line

		Map<String, EquivalentConcepts> equivalentConceptsMap = new HashMap<>();
		while ((line = reader.readLine()) != null) {
			String[] values = line.split("\\t");
			// 0	1				2		3			4			5						6
			// id	effectiveTime	active	moduleId	refsetId	referencedComponentId	mapTarget
			String setId = values[6];
			String conceptIdInSet = values[5];
			EquivalentConcepts equivalentConcepts = equivalentConceptsMap.computeIfAbsent(setId, s -> new EquivalentConcepts(classificationId));
			equivalentConcepts.addConceptId(conceptIdInSet);
		}
		if (!equivalentConceptsMap.isEmpty()) {
			logger.info("Saving {} classification equivalent concept sets", equivalentConceptsMap.size());
			List<List<EquivalentConcepts>> partition = Lists.partition(new ArrayList<>(equivalentConceptsMap.values()), 10_000);
			for (List<EquivalentConcepts> equivalentConcepts : partition) {
				equivalentConceptsRepository.saveAll(equivalentConcepts);
			}
		}
	}
}
