package org.snomed.snowstorm.rest;

import com.fasterxml.jackson.annotation.JsonView;
import io.kaicode.rest.util.branchpathrewrite.BranchPathUriUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.snomed.otf.owltoolkit.conversion.ConversionException;
import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.data.domain.ConceptMini;
import org.snomed.snowstorm.core.data.domain.ConceptView;
import org.snomed.snowstorm.core.data.domain.Relationship;
import org.snomed.snowstorm.core.data.domain.expression.Expression;
import org.snomed.snowstorm.core.data.services.*;
import org.snomed.snowstorm.core.data.services.pojo.AsyncConceptChangeBatch;
import org.snomed.snowstorm.rest.pojo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import javax.validation.Valid;
import java.util.*;

@RestController
@Api(tags = "Concepts", description = "-")
@RequestMapping(produces = "application/json")
public class ConceptController {

	@Autowired
	private ConceptService conceptService;

	@Autowired
	private RelationshipService relationshipService;

	@Autowired
	private QueryService queryService;

	@Autowired
	private ExpressionService expressionService;
	
	@Autowired
	private SemanticIndexUpdateService queryConceptUpdateService;

	@RequestMapping(value = "/{branch}/concepts", method = RequestMethod.GET, produces = {"application/json", "text/csv"})
	@ResponseBody
	public ItemsPage<ConceptMini> findConcepts(
			@PathVariable String branch,
			@RequestParam(required = false) Boolean activeFilter,
			@RequestParam(required = false) String term,
			@RequestParam(required = false) String ecl,
			@RequestParam(required = false) String statedEcl,
			@RequestParam(required = false) String escg,
			@RequestParam(required = false) Set<String> conceptIds,
			@RequestParam(required = false, defaultValue = "0") int offset,
			@RequestParam(required = false, defaultValue = "50") int limit,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		// TODO: Remove this partial ESCG support
		if (ecl == null && escg != null && !escg.isEmpty()) {
			conceptIds = new HashSet<>();
			String[] ids = escg.split("UNION");
			for (String id : ids) {
				conceptIds.add(id.trim());
			}
		}

		boolean stated = false;
		if (statedEcl != null && !statedEcl.isEmpty()) {
			stated = true;
			ecl = statedEcl;
		}

		QueryService.ConceptQueryBuilder queryBuilder = queryService.createQueryBuilder(stated)
				.activeFilter(activeFilter)
				.ecl(ecl)
				.termPrefix(term)
				.languageCodes(ControllerHelper.getLanguageCodes(acceptLanguageHeader))
				.conceptIds(conceptIds);

		return new ItemsPage<>(queryService.search(queryBuilder, BranchPathUriUtil.decodePath(branch), ControllerHelper.getPageRequest(offset, limit)));
	}

	@RequestMapping(value = "/{branch}/concepts/search", method = RequestMethod.POST, produces = {"application/json", "text/csv"})
	@ResponseBody
	public ItemsPage<ConceptMini> search(
			@PathVariable String branch,
			@RequestBody ConceptSearchRequest searchRequest,
			@RequestHeader("Accept-Language") String acceptLanguageHeader) {

		return findConcepts(BranchPathUriUtil.decodePath(branch),
				searchRequest.getActiveFilter(),
				searchRequest.getTermFilter(),
				searchRequest.getEclFilter(),
				searchRequest.getStatedEclFilter(),
				null,
				searchRequest.getConceptIds(),
				searchRequest.getOffset(),
				searchRequest.getLimit(),
				acceptLanguageHeader);
	}

	@RequestMapping(value = "/browser/{branch}/concepts", method = RequestMethod.GET)
	@ResponseBody
	@JsonView(value = View.Component.class)
	public Page<? extends ConceptView> getBrowserConcepts(
			@PathVariable String branch,
			@RequestParam(defaultValue = "0") int number,
			@RequestParam(defaultValue = "100") int size,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		return conceptService.findAll(BranchPathUriUtil.decodePath(branch), ControllerHelper.getLanguageCodes(acceptLanguageHeader), PageRequest.of(number, size));
	}

	@RequestMapping(value = "/browser/{branch}/concepts/bulk-load", method = RequestMethod.POST)
	@ResponseBody
	@JsonView(value = View.Component.class)
	public Collection<Concept> getBrowserConcepts(
			@PathVariable String branch,
			@RequestBody ConceptIdsPojo request,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		return conceptService.find(BranchPathUriUtil.decodePath(branch), request.getConceptIds(), ControllerHelper.getLanguageCodes(acceptLanguageHeader));
	}

	@ResponseBody
	@RequestMapping(value = "/browser/{branch}/concepts/{conceptId}", method = RequestMethod.GET)
	@JsonView(value = View.Component.class)
	public ConceptView findConcept(
			@PathVariable String branch,
			@PathVariable String conceptId,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		return ControllerHelper.throwIfNotFound("Concept", conceptService.find(conceptId, ControllerHelper.getLanguageCodes(acceptLanguageHeader), BranchPathUriUtil.decodePath(branch)));
	}

	@ResponseBody
	@RequestMapping(value = "/{branch}/concepts/{conceptId}/descriptions", method = RequestMethod.GET)
	@JsonView(value = View.Component.class)
	public ConceptDescriptionsResult findConceptDescriptions(
			@PathVariable String branch,
			@PathVariable String conceptId,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		Concept concept = ControllerHelper.throwIfNotFound("Concept", conceptService.find(conceptId, ControllerHelper.getLanguageCodes(acceptLanguageHeader), BranchPathUriUtil.decodePath(branch)));
		return new ConceptDescriptionsResult(concept.getDescriptions());
	}

	@ResponseBody
	@RequestMapping(value = "/{branch}/concepts/{conceptId}/descendants", method = RequestMethod.GET)
	@JsonView(value = View.Component.class)
	public ItemsPage<ConceptMini> findConceptDescendants(@PathVariable String branch,
			@PathVariable String conceptId,
			@RequestParam(required = false, defaultValue = "false") boolean stated,
			@RequestParam(required = false, defaultValue = "0") int offset,
			@RequestParam(required = false, defaultValue = "50") int limit,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		return findConcepts(branch, stated, null, null, "<" + conceptId, null, null, offset, limit, acceptLanguageHeader);
	}

	@ResponseBody
	@RequestMapping(value = "/{branch}/concepts/{conceptId}/inbound-relationships", method = RequestMethod.GET)
	@JsonView(value = View.Component.class)
	public InboundRelationshipsResult findConceptInboundRelationships(@PathVariable String branch, @PathVariable String conceptId) {
		List<Relationship> inboundRelationships = relationshipService.findInboundRelationships(conceptId, BranchPathUriUtil.decodePath(branch), null).getContent();
		return new InboundRelationshipsResult(inboundRelationships);
	}

	@ResponseBody
	@RequestMapping(value = "/browser/{branch}/concepts/{conceptId}", method = RequestMethod.PUT)
	@JsonView(value = View.Component.class)
	public ConceptView updateConcept(
			@PathVariable String branch,
			@PathVariable String conceptId,
			@RequestBody @Valid ConceptView concept,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) throws ServiceException {

		Assert.isTrue(concept.getConceptId() != null && conceptId != null && concept.getConceptId().equals(conceptId), "The conceptId in the " +
				"path must match the one in the request body.");
		return conceptService.update((Concept) concept, ControllerHelper.getLanguageCodes(acceptLanguageHeader), BranchPathUriUtil.decodePath(branch));
	}

	@ResponseBody
	@RequestMapping(value = "/browser/{branch}/concepts", method = RequestMethod.POST)
	@JsonView(value = View.Component.class)
	public ConceptView createConcept(
			@PathVariable String branch,
			@RequestBody @Valid ConceptView concept,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) throws ServiceException {

		return conceptService.create((Concept) concept, ControllerHelper.getLanguageCodes(acceptLanguageHeader), BranchPathUriUtil.decodePath(branch));
	}

	@RequestMapping(value = "/{branch}/concepts/{conceptId}", method = RequestMethod.DELETE)
	public void deleteConcept(@PathVariable String branch, @PathVariable String conceptId) {
		conceptService.deleteConceptAndComponents(conceptId, BranchPathUriUtil.decodePath(branch), false);
	}

	@ApiOperation(value = "Start a bulk concept change.", notes = "Concepts can be created or updated using this endpoint.")
	@ResponseBody
	@RequestMapping(value = "/browser/{branch}/concepts/bulk", method = RequestMethod.POST)
	public ResponseEntity createConceptBulkChange(@PathVariable String branch, @RequestBody @Valid List<ConceptView> concepts, UriComponentsBuilder uriComponentsBuilder) {
		List<Concept> conceptList = new ArrayList<>();
		concepts.forEach(conceptView -> conceptList.add((Concept) conceptView));
		AsyncConceptChangeBatch batchConceptChange = new AsyncConceptChangeBatch();
		conceptService.createUpdateAsync(conceptList, BranchPathUriUtil.decodePath(branch), batchConceptChange, SecurityContextHolder.getContext());
		return ResponseEntity.created(uriComponentsBuilder.path("/browser/{branch}/concepts/bulk/{bulkChangeId}")
				.buildAndExpand(branch, batchConceptChange.getId()).toUri()).build();
	}

	@ApiOperation("Fetch the status of a bulk concept creation or update.")
	@ResponseBody
	@RequestMapping(value = "/browser/{branch}/concepts/bulk/{bulkChangeId}", method = RequestMethod.GET)
	public AsyncConceptChangeBatch getConceptBulkChange(@PathVariable String branch, @PathVariable String bulkChangeId) {
		return ControllerHelper.throwIfNotFound("Bulk Change", conceptService.getBatchConceptChange(bulkChangeId));
	}

	@ResponseBody
	@RequestMapping(value = "/browser/{branch}/concepts/{conceptId}/children", method = RequestMethod.GET)
	@JsonView(value = View.Component.class)
	public Collection<ConceptMini> findConceptChildren(@PathVariable String branch,
			@PathVariable String conceptId,
			@RequestParam(defaultValue = "inferred") Relationship.CharacteristicType form,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		List<String> languageCodes = ControllerHelper.getLanguageCodes(acceptLanguageHeader);
		return conceptService.findConceptChildren(conceptId, languageCodes, BranchPathUriUtil.decodePath(branch), form);
	}

	@ResponseBody
	@RequestMapping(value = "/browser/{branch}/concepts/{conceptId}/parents", method = RequestMethod.GET)
	@JsonView(value = View.Component.class)
	public Collection<ConceptMini> findConceptParents(@PathVariable String branch,
			@PathVariable String conceptId,
			@RequestParam(defaultValue = "inferred") Relationship.CharacteristicType form,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		List<String> languageCodes = ControllerHelper.getLanguageCodes(acceptLanguageHeader);
		return conceptService.findConceptParents(conceptId, languageCodes, BranchPathUriUtil.decodePath(branch), form);
	}

	@ResponseBody
	@RequestMapping(value = "/browser/{branch}/concepts/{conceptId}/ancestors", method = RequestMethod.GET)
	@JsonView(value = View.Component.class)
	public Collection<ConceptMini> findConceptAncestors(@PathVariable String branch,
			@PathVariable String conceptId,
			@RequestParam(defaultValue = "inferred") Relationship.CharacteristicType form,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		String branchPath = BranchPathUriUtil.decodePath(branch);
		Set<Long> ancestorIds = queryService.findAncestorIds(conceptId, branchPath, form == Relationship.CharacteristicType.stated);
		return conceptService.findConceptMinis(branchPath, ancestorIds, ControllerHelper.getLanguageCodes(acceptLanguageHeader)).getResultsMap().values();
	}

	@RequestMapping(value = "/rebuild/{branch}", method = RequestMethod.POST)
	public void rebuildBranchTransitiveClosure(@PathVariable String branch) throws ConversionException {
		queryConceptUpdateService.rebuildStatedAndInferredSemanticIndex(BranchPathUriUtil.decodePath(branch));
	}
	
	@ResponseBody
	@RequestMapping(value = "/{branch}/concepts/{conceptId}/authoring-form", method = RequestMethod.GET)
	public Expression getConceptAuthoringForm(
			@PathVariable String branch,
			@PathVariable String conceptId,
			@RequestHeader(value = "Accept-Language", defaultValue = ControllerHelper.DEFAULT_ACCEPT_LANG_HEADER) String acceptLanguageHeader) {

		List<String> languageCodes = ControllerHelper.getLanguageCodes(acceptLanguageHeader);
		return expressionService.getConceptAuthoringForm(conceptId, languageCodes, BranchPathUriUtil.decodePath(branch));
	}

}
