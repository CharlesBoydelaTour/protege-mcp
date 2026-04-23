package org.protege.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.protege.editor.core.ProtegeManager;
import org.protege.editor.core.editorkit.EditorKit;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Concrete MCP tool executor that bridges JSON-RPC tool calls to the live
 * Protege OWL model manager. One instance is created per OSGi bundle
 * activation and shared across all MCP sessions.
 *
 * <p>
 * Sandbox model: each workspace (identified by its ontology IRI string)
 * owns at most one sandbox – an ordered list of pending {@link AddAxiom}
 * changes. Write tools accumulate changes in the sandbox by default.
 * {@code sandbox_commit} promotes them to the live ontology.
 */
public class ProtegeMcpToolExecutor implements McpToolExecutor {

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /** Thrown by tool implementations to produce a structured JSON-RPC error. */
    public static final class McpException extends RuntimeException {
        private final int code;
        private final JsonNode data;

        public McpException(int code, String message, JsonNode data) {
            super(message);
            this.code = code;
            this.data = data;
        }

        public int getCode() {
            return code;
        }

        public JsonNode getData() {
            return data;
        }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Pending sandbox axioms: ontologyId -> ordered list of axiom additions. */
    private final Map<String, List<OWLAxiom>> sandboxes = new ConcurrentHashMap<>();

    private static final List<ObjectNode> TOOL_DEFS = buildToolDefs();

    // -----------------------------------------------------------------------
    // McpToolExecutor contract
    // -----------------------------------------------------------------------

    @Override
    public List<ObjectNode> listTools() {
        return TOOL_DEFS;
    }

    @Override
    public JsonNode execute(String toolName, JsonNode args) throws Exception {
        switch (toolName) {
            case "server_info":
                return serverInfo();
            case "ontology_list":
                return ontologyList();
            case "ontology_open":
                return ontologyOpen(args);
            case "ontology_close":
                return ontologyClose(args);
            case "ontology_info":
                return ontologyInfo(args);
            case "ontology_capabilities":
                return ontologyCapabilities(args);
            case "workspace_status":
                return workspaceStatus(args);
            case "entity_search":
                return entitySearch(args);
            case "entity_get":
                return entityGet(args);
            case "hierarchy_get":
                return hierarchyGet(args);
            case "axioms_list":
                return axiomsList(args);
            case "dl_query":
                return dlQuery(args);
            case "class_create":
                return classCreate(args);
            case "property_create":
                return propertyCreate(args);
            case "individual_create":
                return individualCreate(args);
            case "individual_assert_type":
                return individualAssertType(args);
            case "annotation_set":
                return annotationSet(args);
            case "ontology_validate":
                return ontologyValidate(args);
            case "consistency_check":
                return consistencyCheck(args);
            case "reasoner_classify":
                return reasonerClassify(args);
            case "ontology_save":
                return ontologySave(args);
            case "ontology_export":
                return ontologyExport(args);
            case "sandbox_commit":
                return sandboxCommit(args);
            case "class_delete":
                return classDelete(args);
            case "class_rename":
                return classRename(args);
            case "object_property_create":
                return objectPropertyCreate(args);
            case "object_property_delete":
                return objectPropertyDelete(args);
            case "object_property_rename":
                return objectPropertyRename(args);
            case "data_property_create":
                return dataPropertyCreate(args);
            case "data_property_delete":
                return dataPropertyDelete(args);
            case "data_property_rename":
                return dataPropertyRename(args);
            case "entity_annotate_set":
                return entityAnnotateSet(args);
            case "entity_annotate_remove":
                return entityAnnotateRemove(args);
            case "ontology_reload":
                return ontologyReload(args);
            case "individual_delete":
                return individualDelete(args);
            case "individual_rename":
                return individualRename(args);
            case "axiom_add":
                return axiomAdd(args);
            case "axiom_remove":
                return axiomRemove(args);
            default:
                throw new McpException(-32601, "Unknown tool: " + toolName, null);
        }
    }

    // -----------------------------------------------------------------------
    // Tool implementations – read / introspection
    // -----------------------------------------------------------------------

    private JsonNode serverInfo() {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("name", "protege-mcp");
        r.put("version", ProtegeMcpVersion.get());
        r.put("transport", "stdio");
        r.put("mcp_protocol_version", "2025-03-26");
        r.put("attached", true);
        r.put("sandbox_per_workspace", true);
        ArrayNode caps = r.putArray("capabilities");
        caps.add("ontology_read");
        caps.add("ontology_write");
        caps.add("reasoning");
        caps.add("sandbox");
        return r;
    }

    private JsonNode ontologyList() {
        OWLModelManager mm = requireModelManager();
        ArrayNode list = MAPPER.createArrayNode();
        for (OWLOntology ont : mm.getOntologies()) {
            ObjectNode entry = MAPPER.createObjectNode();
            String id = ontologyIdFor(ont);
            entry.put("ontology_id", id);
            entry.put("iri", id);
            entry.put("dirty", mm.isDirty(ont));
            entry.put("active", ont.equals(mm.getActiveOntology()));
            entry.put("sandbox_present", sandboxes.containsKey(id));
            entry.put("axiom_count", ont.getAxiomCount());
            URI physURI = mm.getOntologyPhysicalURI(ont);
            if (physURI != null) {
                entry.put("physical_uri", physURI.toString());
            }
            list.add(entry);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("ontologies", list);
        return result;
    }

    private JsonNode ontologyOpen(JsonNode args) {
        String uri = firstRequiredString(args, "source", "uri");
        OWLModelManager mm = requireModelManager();
        try {
            IRI iri = IRI.create(uri);
            OWLOntology ont = mm.getOWLOntologyManager().loadOntologyFromOntologyDocument(iri);
            // Make the freshly loaded ontology the active one so workspace
            // services (entity finder, class hierarchy provider, renderer)
            // index it and subsequent reads/writes see it.
            try {
                mm.setActiveOntology(ont);
            } catch (RuntimeException ignored) {
                // Defensive: do not fail the open if the workspace refuses
                // an active-ontology switch (e.g. during shutdown).
            }
            ObjectNode r = MAPPER.createObjectNode();
            r.put("ontology_id", ontologyIdFor(ont));
            r.put("axiom_count", ont.getAxiomCount());
            r.put("loaded", true);
            return r;
        } catch (OWLOntologyAlreadyExistsException e) {
            OWLOntology ont = mm.getOWLOntologyManager().getOntology(e.getOntologyID());
            if (ont != null) {
                try {
                    mm.setActiveOntology(ont);
                } catch (RuntimeException ignored) {
                }
            }
            ObjectNode r = MAPPER.createObjectNode();
            r.put("ontology_id", ontologyIdFor(ont));
            r.put("already_open", true);
            return r;
        } catch (OWLOntologyCreationException e) {
            throw new McpException(-32010, "Failed to open ontology: " + e.getMessage(), null);
        }
    }

    private JsonNode ontologyClose(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        sandboxes.remove(ontId);
        boolean removed = mm.removeOntology(ont);
        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("closed", removed);
        return r;
    }

    private JsonNode ontologyInfo(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("axiom_count", ont.getAxiomCount());
        r.put("logical_axiom_count", ont.getLogicalAxiomCount());
        r.put("class_count", ont.getClassesInSignature().size());
        r.put("object_property_count", ont.getObjectPropertiesInSignature().size());
        r.put("data_property_count", ont.getDataPropertiesInSignature().size());
        r.put("individual_count", ont.getIndividualsInSignature().size());
        r.put("dirty", mm.isDirty(ont));
        r.put("sandbox_present", sandboxes.containsKey(ontId));
        int pending = sandboxes.containsKey(ontId) ? sandboxes.get(ontId).size() : 0;
        r.put("sandbox_pending_changes", pending);
        URI physURI = mm.getOntologyPhysicalURI(ont);
        if (physURI != null) {
            r.put("physical_uri", physURI.toString());
        }
        OWLDocumentFormat fmt = mm.getOWLOntologyManager().getOntologyFormat(ont);
        if (fmt != null) {
            r.put("format", fmt.getClass().getSimpleName());
        }
        return r;
    }

    private JsonNode ontologyCapabilities(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("reads_supported", true);
        r.put("writes_supported", mm.isMutable(ont));
        r.put("sandbox_supported", true);
        r.put("reasoner_available", mm.getReasoner() != null);
        ArrayNode exports = r.putArray("export_formats");
        exports.add("RDF/XML").add("Turtle").add("Manchester");
        return r;
    }

    private JsonNode workspaceStatus(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        List<OWLAxiom> pending = sandboxes.getOrDefault(ontId, Collections.emptyList());
        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("dirty", mm.isDirty(ont));
        r.put("locked", !mm.isMutable(ont));
        r.put("sandbox_present", sandboxes.containsKey(ontId));
        r.put("pending_changes", pending.size());
        return r;
    }

    // -----------------------------------------------------------------------
    // Tool implementations – entity queries
    // -----------------------------------------------------------------------

    private JsonNode entitySearch(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String query = requireString(args, "query");
        OWLModelManager mm = requireModelManager();
        resolveOntology(ontId, mm); // validate
        Set<OWLEntity> matches = mm.getOWLEntityFinder().getMatchingOWLEntities(query);
        int limit = args.path("limit").asInt(50);
        ArrayNode items = MAPPER.createArrayNode();
        int count = 0;
        for (OWLEntity e : matches) {
            if (count++ >= limit)
                break;
            items.add(entityNode(e, mm));
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.set("results", items);
        r.put("total", matches.size());
        return r;
    }

    private JsonNode entityGet(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String entityIri = requireString(args, "entity_iri");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        IRI iri = IRI.create(entityIri);
        Set<OWLEntity> entities = mm.getOWLEntityFinder().getEntities(iri);
        if (entities.isEmpty()) {
            throw new McpException(-32010, "Entity not found: " + entityIri,
                    MAPPER.createObjectNode().put("entity_iri", entityIri));
        }
        OWLEntity entity = entities.iterator().next();
        ObjectNode r = entityNode(entity, mm);
        ArrayNode annotations = r.putArray("annotations");
        for (OWLAnnotation ann : EntitySearcher.getAnnotations(entity, ont)) {
            ObjectNode an = MAPPER.createObjectNode();
            an.put("property", ann.getProperty().getIRI().toString());
            an.put("value", ann.getValue().toString());
            annotations.add(an);
        }
        return r;
    }

    private JsonNode hierarchyGet(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String classIri = firstRequiredString(args, "entity_iri", "class_iri");
        OWLModelManager mm = requireModelManager();
        resolveOntology(ontId, mm);
        IRI iri = IRI.create(classIri);
        OWLClass cls = mm.getOWLDataFactory().getOWLClass(iri);
        var hp = mm.getOWLHierarchyManager().getOWLClassHierarchyProvider();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("entity_iri", classIri);
        r.put("rendering", mm.getRendering(cls));
        ArrayNode parents = r.putArray("parents");
        for (OWLClass p : hp.getParents(cls)) {
            parents.add(classNode(p, mm));
        }
        ArrayNode children = r.putArray("children");
        for (OWLClass c : hp.getChildren(cls)) {
            children.add(classNode(c, mm));
        }
        return r;
    }

    private JsonNode axiomsList(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        int limit = args.path("limit").asInt(100);

        SimpleShortFormProvider sfp = new SimpleShortFormProvider();
        Set<OWLAxiom> axiomSet;
        if (args.has("entity_iri") && !args.path("entity_iri").asText("").isEmpty()) {
            IRI entityIri = IRI.create(args.path("entity_iri").asText());
            Set<OWLEntity> entities = mm.getOWLEntityFinder().getEntities(entityIri);
            axiomSet = new LinkedHashSet<>();
            for (OWLEntity e : entities) {
                axiomSet.addAll(ont.getReferencingAxioms(e));
            }
        } else {
            axiomSet = ont.getAxioms();
        }

        ArrayNode items = MAPPER.createArrayNode();
        int count = 0;
        for (OWLAxiom ax : axiomSet) {
            if (count++ >= limit)
                break;
            ObjectNode an = MAPPER.createObjectNode();
            an.put("type", ax.getAxiomType().getName());
            an.put("axiom", ax.toString());
            items.add(an);
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.set("axioms", items);
        r.put("total", axiomSet.size());
        r.put("truncated", axiomSet.size() > limit);
        return r;
    }

    private JsonNode dlQuery(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String expr = requireString(args, "expression");
        String mode = args.path("mode").asText("instances");
        OWLModelManager mm = requireModelManager();
        resolveOntology(ontId, mm);

        OWLClassExpression ce;
        try {
            ce = mm.getOWLExpressionCheckerFactory()
                    .getOWLClassExpressionChecker()
                    .createObject(expr);
        } catch (Exception e) {
            throw new McpException(-32602, "Invalid DL expression: " + e.getMessage(), null);
        }

        OWLReasoner reasoner = mm.getReasoner();
        if (reasoner == null) {
            throw new McpException(-32000, "No active reasoner", null);
        }

        ObjectNode r = MAPPER.createObjectNode();
        r.put("expression", expr);
        r.put("mode", mode);

        if ("subclasses".equals(mode)) {
            ArrayNode arr = r.putArray("subclasses");
            reasoner.getSubClasses(ce, false).getFlattened()
                    .stream().filter(c -> !c.isOWLNothing())
                    .forEach(c -> arr.add(classNode(c, mm)));
        } else {
            ArrayNode arr = r.putArray("instances");
            reasoner.getInstances(ce, false).getFlattened()
                    .forEach(ind -> arr.add(mm.getRendering(ind)));
        }
        return r;
    }

    // -----------------------------------------------------------------------
    // Tool implementations – writes (sandbox-first by default)
    // -----------------------------------------------------------------------

    private JsonNode classCreate(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String classIri = requireString(args, "class_iri");
        boolean dryRun = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));

        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(cls)));

        if (args.has("parent_class_iris") && args.path("parent_class_iris").isArray()) {
            for (JsonNode parentNode : args.path("parent_class_iris")) {
                String parentIri = parentNode.asText("");
                if (!parentIri.isEmpty()) {
                    OWLClass parent = df.getOWLClass(IRI.create(parentIri));
                    changes.add(new AddAxiom(ont, df.getOWLSubClassOfAxiom(cls, parent)));
                }
            }
        } else if (args.has("parent_iri") && !args.path("parent_iri").asText("").isEmpty()) {
            OWLClass parent = df.getOWLClass(IRI.create(args.path("parent_iri").asText()));
            changes.add(new AddAxiom(ont, df.getOWLSubClassOfAxiom(cls, parent)));
        }

        applyOrQueue(ontId, args, changes, mm, dryRun);

        ObjectNode r = MAPPER.createObjectNode();
        r.put("class_iri", classIri);
        r.put("changes", changes.size());
        r.put("dry_run", dryRun);
        r.put("sandboxed", !dryRun && !args.path("direct").asBoolean(false));
        return r;
    }

    private JsonNode propertyCreate(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String propIri = requireString(args, "property_iri");
        String kind = firstString(args, "property_kind", "kind");
        if (kind == null || kind.isEmpty()) {
            kind = "object";
        }
        boolean dryRun = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        IRI iri = IRI.create(propIri);

        List<OWLOntologyChange> changes = new ArrayList<>();
        OWLEntity prop;
        switch (kind) {
            case "data":
                prop = df.getOWLDataProperty(iri);
                changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(prop)));
                break;
            case "annotation":
                prop = df.getOWLAnnotationProperty(iri);
                changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(prop)));
                break;
            default:
                prop = df.getOWLObjectProperty(iri);
                changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(prop)));
        }

        applyOrQueue(ontId, args, changes, mm, dryRun);

        ObjectNode r = MAPPER.createObjectNode();
        r.put("property_iri", propIri);
        r.put("kind", kind);
        r.put("changes", changes.size());
        r.put("dry_run", dryRun);
        return r;
    }

    private JsonNode individualCreate(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String indIri = requireString(args, "individual_iri");
        boolean dryRun = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(indIri));

        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(ind)));

        if (args.has("type_iris") && args.path("type_iris").isArray()) {
            for (JsonNode typeNode : args.path("type_iris")) {
                String typeIri = typeNode.asText("");
                if (!typeIri.isEmpty()) {
                    OWLClass type = df.getOWLClass(IRI.create(typeIri));
                    changes.add(new AddAxiom(ont, df.getOWLClassAssertionAxiom(type, ind)));
                }
            }
        } else if (args.has("type_iri") && !args.path("type_iri").asText("").isEmpty()) {
            OWLClass type = df.getOWLClass(IRI.create(args.path("type_iri").asText()));
            changes.add(new AddAxiom(ont, df.getOWLClassAssertionAxiom(type, ind)));
        }

        applyOrQueue(ontId, args, changes, mm, dryRun);

        ObjectNode r = MAPPER.createObjectNode();
        r.put("individual_iri", indIri);
        r.put("changes", changes.size());
        r.put("dry_run", dryRun);
        return r;
    }

    private JsonNode individualAssertType(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String indIri = requireString(args, "individual_iri");
        String typeIri = firstRequiredString(args, "class_iri", "type_iri");
        boolean dryRun = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();

        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(indIri));
        OWLClass type = df.getOWLClass(IRI.create(typeIri));
        List<OWLOntologyChange> changes = Collections.singletonList(
                new AddAxiom(ont, df.getOWLClassAssertionAxiom(type, ind)));

        applyOrQueue(ontId, args, changes, mm, dryRun);

        ObjectNode r = MAPPER.createObjectNode();
        r.put("individual_iri", indIri);
        r.put("type_iri", typeIri);
        r.put("dry_run", dryRun);
        return r;
    }

    private JsonNode annotationSet(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String subjIri = firstRequiredString(args, "target_iri", "subject_iri");
        String propIri = requireString(args, "property_iri");
        String value = requireString(args, "value");
        boolean dryRun = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();

        OWLAnnotationProperty prop = df.getOWLAnnotationProperty(IRI.create(propIri));
        OWLAnnotationValue annVal = df.getOWLLiteral(value);
        OWLAxiom axiom = df.getOWLAnnotationAssertionAxiom(prop, IRI.create(subjIri), annVal);
        List<OWLOntologyChange> changes = Collections.singletonList(new AddAxiom(ont, axiom));

        applyOrQueue(ontId, args, changes, mm, dryRun);

        ObjectNode r = MAPPER.createObjectNode();
        r.put("subject_iri", subjIri);
        r.put("property_iri", propIri);
        r.put("value", value);
        r.put("dry_run", dryRun);
        return r;
    }

    // -----------------------------------------------------------------------
    // Tool implementations – validation and reasoning
    // -----------------------------------------------------------------------

    private JsonNode ontologyValidate(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);

        List<String> issues = new ArrayList<>();
        // Check for anonymous ontology
        if (!ont.getOntologyID().getOntologyIRI().isPresent()) {
            issues.add("Ontology has no IRI (anonymous).");
        }
        // Check for version IRI without ontology IRI
        if (ont.getOntologyID().getVersionIRI().isPresent() && !ont.getOntologyID().getOntologyIRI().isPresent()) {
            issues.add("Version IRI present but no ontology IRI.");
        }

        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("valid", issues.isEmpty());
        r.put("issue_count", issues.size());
        ArrayNode arr = r.putArray("issues");
        issues.forEach(arr::add);
        return r;
    }

    private JsonNode consistencyCheck(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        resolveOntology(ontId, mm);
        OWLReasoner reasoner = requireReasoner(mm);

        boolean consistent = reasoner.isConsistent();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("consistent", consistent);
        if (!consistent) {
            r.put("note", "Use dl_query or reasoner_classify for more details.");
        }
        return r;
    }

    private JsonNode reasonerClassify(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        resolveOntology(ontId, mm);
        OWLReasoner reasoner = requireReasoner(mm);

        try {
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
        } catch (UnsupportedOperationException ignored) {
            // Some reasoners do not support explicit precomputation; results
            // are still available on demand.
        }

        boolean consistent = reasoner.isConsistent();
        Set<OWLClass> unsatisfiable = consistent
                ? reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()
                : Collections.emptySet();

        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("consistent", consistent);
        r.put("unsatisfiable_count", unsatisfiable.size());
        ArrayNode arr = r.putArray("unsatisfiable_classes");
        unsatisfiable.forEach(c -> arr.add(classNode(c, mm)));
        return r;
    }

    private JsonNode ontologySave(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        try {
            mm.save(ont);
        } catch (OWLOntologyStorageException e) {
            throw new McpException(-32000, "Save failed: " + e.getMessage(), null);
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("saved", true);
        return r;
    }

    private JsonNode ontologyExport(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String format = args.path("format").asText("Turtle");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);

        OWLDocumentFormat docFormat;
        switch (format.toLowerCase()) {
            case "rdf/xml":
            case "rdfxml":
                docFormat = new RDFXMLDocumentFormat();
                break;
            case "manchester":
                docFormat = new ManchesterSyntaxDocumentFormat();
                break;
            default:
                docFormat = new TurtleDocumentFormat();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            mm.getOWLOntologyManager().saveOntology(ont, docFormat, baos);
        } catch (OWLOntologyStorageException e) {
            throw new McpException(-32000, "Export failed: " + e.getMessage(), null);
        }

        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("format", format);
        r.put("content", baos.toString(java.nio.charset.StandardCharsets.UTF_8));
        return r;
    }

    private JsonNode sandboxCommit(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        boolean dryRun = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);

        List<OWLAxiom> pending = sandboxes.getOrDefault(ontId, Collections.emptyList());
        if (pending.isEmpty()) {
            ObjectNode r = MAPPER.createObjectNode();
            r.put("ontology_id", ontId);
            r.put("committed_changes", 0);
            r.put("dry_run", dryRun);
            return r;
        }

        if (!dryRun) {
            final List<OWLOntologyChange> changes = pending.stream()
                    .map(ax -> (OWLOntologyChange) new AddAxiom(ont, ax))
                    .collect(Collectors.toList());
            final OWLModelManager mmFinal = mm;
            runOnEdt(() -> mmFinal.applyChanges(changes));
            sandboxes.remove(ontId);
        }

        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("committed_changes", pending.size());
        r.put("dry_run", dryRun);
        return r;
    }

    // -----------------------------------------------------------------------
    // Tool implementations – V1.5 direct-mode edits
    // -----------------------------------------------------------------------

    private JsonNode classDelete(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String classIri = requireString(args, "class_iri");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));
        Set<OWLAxiom> referencing = ont.getReferencingAxioms(cls);
        final List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLAxiom ax : referencing) {
            changes.add(new RemoveAxiom(ont, ax));
        }
        if (!changes.isEmpty()) {
            runOnEdt(() -> mm.applyChanges(changes));
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.put("class_iri", classIri);
        r.put("removed_axioms", changes.size());
        return r;
    }

    @SuppressWarnings("unchecked")
    private JsonNode classRename(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String oldIri = requireString(args, "old_iri");
        final String newIri = requireString(args, "new_iri");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        final OWLClass oldCls = df.getOWLClass(IRI.create(oldIri));
        final List<OWLOntologyChange>[] changesHolder = new List[] { null };
        runOnEdt(() -> {
            org.semanticweb.owlapi.util.OWLEntityRenamer renamer = new org.semanticweb.owlapi.util.OWLEntityRenamer(
                    mm.getOWLOntologyManager(), Collections.singleton(ont));
            List<OWLOntologyChange> changes = renamer.changeIRI(oldCls, IRI.create(newIri));
            if (!changes.isEmpty()) {
                mm.applyChanges(changes);
            }
            changesHolder[0] = changes;
        });
        int applied = changesHolder[0] == null ? 0 : changesHolder[0].size();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("old_iri", oldIri);
        r.put("new_iri", newIri);
        r.put("applied_changes", applied);
        return r;
    }

    private JsonNode objectPropertyCreate(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String propIri = requireString(args, "property_iri");
        final OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(propIri));
        final List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(prop)));
        String parent = firstString(args, "parent_iri");
        if (parent != null && !parent.isEmpty()) {
            OWLObjectProperty parentProp = df.getOWLObjectProperty(IRI.create(parent));
            changes.add(new AddAxiom(ont, df.getOWLSubObjectPropertyOfAxiom(prop, parentProp)));
        }
        runOnEdt(() -> mm.applyChanges(changes));
        ObjectNode r = MAPPER.createObjectNode();
        r.put("property_iri", propIri);
        r.put("changes", changes.size());
        return r;
    }

    private JsonNode objectPropertyDelete(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String propIri = requireString(args, "property_iri");
        final OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLObjectProperty prop = df.getOWLObjectProperty(IRI.create(propIri));
        Set<OWLAxiom> referencing = ont.getReferencingAxioms(prop);
        final List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLAxiom ax : referencing) {
            changes.add(new RemoveAxiom(ont, ax));
        }
        if (!changes.isEmpty()) {
            runOnEdt(() -> mm.applyChanges(changes));
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.put("property_iri", propIri);
        r.put("removed_axioms", changes.size());
        return r;
    }

    @SuppressWarnings("unchecked")
    private JsonNode objectPropertyRename(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String oldIri = requireString(args, "old_iri");
        final String newIri = requireString(args, "new_iri");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        final OWLObjectProperty old = df.getOWLObjectProperty(IRI.create(oldIri));
        final List<OWLOntologyChange>[] changesHolder = new List[] { null };
        runOnEdt(() -> {
            org.semanticweb.owlapi.util.OWLEntityRenamer renamer = new org.semanticweb.owlapi.util.OWLEntityRenamer(
                    mm.getOWLOntologyManager(), Collections.singleton(ont));
            List<OWLOntologyChange> changes = renamer.changeIRI(old, IRI.create(newIri));
            if (!changes.isEmpty()) {
                mm.applyChanges(changes);
            }
            changesHolder[0] = changes;
        });
        int applied = changesHolder[0] == null ? 0 : changesHolder[0].size();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("old_iri", oldIri);
        r.put("new_iri", newIri);
        r.put("applied_changes", applied);
        return r;
    }

    private JsonNode dataPropertyCreate(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String propIri = requireString(args, "property_iri");
        final OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLDataProperty prop = df.getOWLDataProperty(IRI.create(propIri));
        final List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(prop)));
        String parent = firstString(args, "parent_iri");
        if (parent != null && !parent.isEmpty()) {
            OWLDataProperty parentProp = df.getOWLDataProperty(IRI.create(parent));
            changes.add(new AddAxiom(ont, df.getOWLSubDataPropertyOfAxiom(prop, parentProp)));
        }
        runOnEdt(() -> mm.applyChanges(changes));
        ObjectNode r = MAPPER.createObjectNode();
        r.put("property_iri", propIri);
        r.put("changes", changes.size());
        return r;
    }

    private JsonNode dataPropertyDelete(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String propIri = requireString(args, "property_iri");
        final OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLDataProperty prop = df.getOWLDataProperty(IRI.create(propIri));
        Set<OWLAxiom> referencing = ont.getReferencingAxioms(prop);
        final List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLAxiom ax : referencing) {
            changes.add(new RemoveAxiom(ont, ax));
        }
        if (!changes.isEmpty()) {
            runOnEdt(() -> mm.applyChanges(changes));
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.put("property_iri", propIri);
        r.put("removed_axioms", changes.size());
        return r;
    }

    @SuppressWarnings("unchecked")
    private JsonNode dataPropertyRename(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String oldIri = requireString(args, "old_iri");
        final String newIri = requireString(args, "new_iri");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        final OWLDataProperty old = df.getOWLDataProperty(IRI.create(oldIri));
        final List<OWLOntologyChange>[] changesHolder = new List[] { null };
        runOnEdt(() -> {
            org.semanticweb.owlapi.util.OWLEntityRenamer renamer = new org.semanticweb.owlapi.util.OWLEntityRenamer(
                    mm.getOWLOntologyManager(), Collections.singleton(ont));
            List<OWLOntologyChange> changes = renamer.changeIRI(old, IRI.create(newIri));
            if (!changes.isEmpty()) {
                mm.applyChanges(changes);
            }
            changesHolder[0] = changes;
        });
        int applied = changesHolder[0] == null ? 0 : changesHolder[0].size();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("old_iri", oldIri);
        r.put("new_iri", newIri);
        r.put("applied_changes", applied);
        return r;
    }

    private JsonNode entityAnnotateSet(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String entityIri = requireString(args, "entity_iri");
        String property = requireString(args, "property");
        String value = requireString(args, "value");
        String lang = args.path("lang").asText("");
        if (!"rdfs:label".equals(property) && !"rdfs:comment".equals(property)) {
            throw new McpException(-32602, "property must be rdfs:label or rdfs:comment", null);
        }
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        IRI iri = IRI.create(entityIri);
        boolean exists = ont.containsClassInSignature(iri)
                || ont.containsObjectPropertyInSignature(iri)
                || ont.containsDataPropertyInSignature(iri)
                || ont.containsIndividualInSignature(iri)
                || ont.containsAnnotationPropertyInSignature(iri);
        if (!exists) {
            throw new McpException(-32010, "Entity not found: " + entityIri, null);
        }
        OWLAnnotationProperty annProp = "rdfs:label".equals(property)
                ? df.getRDFSLabel()
                : df.getRDFSComment();
        OWLLiteral literal = (lang != null && !lang.isEmpty())
                ? df.getOWLLiteral(value, lang)
                : df.getOWLLiteral(value);

        List<OWLOntologyChange> changes = new ArrayList<>();
        int removed = 0;
        for (OWLAnnotationAssertionAxiom ax : ont.getAnnotationAssertionAxioms(iri)) {
            if (!ax.getProperty().equals(annProp))
                continue;
            if (!ax.getValue().asLiteral().isPresent())
                continue;
            String litLang = ax.getValue().asLiteral().get().getLang();
            if (litLang == null)
                litLang = "";
            if (litLang.equals(lang)) {
                changes.add(new RemoveAxiom(ont, ax));
                removed++;
            }
        }
        OWLAxiom add = df.getOWLAnnotationAssertionAxiom(annProp, iri, literal);
        changes.add(new AddAxiom(ont, add));
        final OWLModelManager mmFinal = mm;
        final List<OWLOntologyChange> changesFinal = changes;
        runOnEdt(() -> mmFinal.applyChanges(changesFinal));

        ObjectNode r = MAPPER.createObjectNode();
        r.put("entity_iri", entityIri);
        r.put("property", property);
        r.put("value", value);
        r.put("lang", lang);
        r.put("removed", removed);
        r.put("added", 1);
        return r;
    }

    private JsonNode entityAnnotateRemove(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String entityIri = requireString(args, "entity_iri");
        String property = requireString(args, "property");
        String lang = args.path("lang").asText("");
        boolean langSpecified = args.has("lang") && !args.path("lang").asText("").isEmpty();
        if (!"rdfs:label".equals(property) && !"rdfs:comment".equals(property)) {
            throw new McpException(-32602, "property must be rdfs:label or rdfs:comment", null);
        }
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        IRI iri = IRI.create(entityIri);
        OWLAnnotationProperty annProp = "rdfs:label".equals(property)
                ? df.getRDFSLabel()
                : df.getRDFSComment();

        List<OWLOntologyChange> changes = new ArrayList<>();
        int removed = 0;
        for (OWLAnnotationAssertionAxiom ax : ont.getAnnotationAssertionAxioms(iri)) {
            if (!ax.getProperty().equals(annProp))
                continue;
            if (langSpecified) {
                if (!ax.getValue().asLiteral().isPresent())
                    continue;
                String litLang = ax.getValue().asLiteral().get().getLang();
                if (litLang == null)
                    litLang = "";
                if (!litLang.equals(lang))
                    continue;
            }
            changes.add(new RemoveAxiom(ont, ax));
            removed++;
        }
        if (!changes.isEmpty()) {
            final OWLModelManager mmFinal = mm;
            final List<OWLOntologyChange> changesFinal = changes;
            runOnEdt(() -> mmFinal.applyChanges(changesFinal));
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.put("entity_iri", entityIri);
        r.put("property", property);
        r.put("removed", removed);
        return r;
    }

    private JsonNode ontologyReload(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        IRI docIri = mm.getOWLOntologyManager().getOntologyDocumentIRI(ont);
        if (docIri == null) {
            throw new McpException(-32000, "Cannot reload: no document IRI for " + ontId, null);
        }
        final String physicalUri = docIri.toString();
        final IRI docIriFinal = docIri;
        sandboxes.remove(ontId);
        final OWLOntology[] reloadedHolder = new OWLOntology[1];
        final Throwable[] errHolder = new Throwable[1];
        runOnEdt(() -> {
            try {
                // Use ModelManager so workspace caches are invalidated and listeners notified.
                mm.removeOntology(ont);
                OWLOntology reloaded = mm.getOWLOntologyManager()
                        .loadOntologyFromOntologyDocument(docIriFinal);
                try {
                    mm.setActiveOntology(reloaded);
                } catch (RuntimeException ignored) {
                }
                reloadedHolder[0] = reloaded;
            } catch (Exception e) {
                errHolder[0] = e;
            }
        });
        if (errHolder[0] != null) {
            throw new McpException(-32000, "Reload failed: " + errHolder[0].getMessage(), null);
        }
        OWLOntology reloaded = reloadedHolder[0];
        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontologyIdFor(reloaded));
        r.put("physical_uri", physicalUri);
        r.put("axiom_count", reloaded.getAxiomCount());
        r.put("reloaded", true);
        return r;
    }

    // -----------------------------------------------------------------------
    // Tool implementations – V1.5 individuals + axiom-level (Manchester)
    // -----------------------------------------------------------------------

    private JsonNode individualDelete(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String indIri = requireString(args, "individual_iri");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(indIri));
        Set<OWLAxiom> referencing = ont.getReferencingAxioms(ind);
        final List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLAxiom ax : referencing) {
            changes.add(new RemoveAxiom(ont, ax));
        }
        if (!changes.isEmpty()) {
            runOnEdt(() -> mm.applyChanges(changes));
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.put("individual_iri", indIri);
        r.put("removed_axioms", changes.size());
        return r;
    }

    @SuppressWarnings("unchecked")
    private JsonNode individualRename(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String oldIri = requireString(args, "old_iri");
        final String newIri = requireString(args, "new_iri");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        final OWLNamedIndividual old = df.getOWLNamedIndividual(IRI.create(oldIri));
        final List<OWLOntologyChange>[] changesHolder = new List[] { null };
        runOnEdt(() -> {
            org.semanticweb.owlapi.util.OWLEntityRenamer renamer = new org.semanticweb.owlapi.util.OWLEntityRenamer(
                    mm.getOWLOntologyManager(), Collections.singleton(ont));
            List<OWLOntologyChange> changes = renamer.changeIRI(old, IRI.create(newIri));
            if (!changes.isEmpty()) {
                mm.applyChanges(changes);
            }
            changesHolder[0] = changes;
        });
        int applied = changesHolder[0] == null ? 0 : changesHolder[0].size();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("old_iri", oldIri);
        r.put("new_iri", newIri);
        r.put("applied_changes", applied);
        return r;
    }

    private JsonNode axiomAdd(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String axiomStr = requireString(args, "axiom");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        final OWLAxiom parsed = parseManchesterAxiom(ont, mm.getOWLDataFactory(), axiomStr);
        runOnEdt(() -> mm.applyChanges(Collections.<OWLOntologyChange>singletonList(new AddAxiom(ont, parsed))));
        ObjectNode r = MAPPER.createObjectNode();
        r.put("axiom", axiomStr);
        r.put("added", true);
        return r;
    }

    private JsonNode axiomRemove(JsonNode args) {
        String ontId = requireString(args, "ontology_id");
        String axiomStr = requireString(args, "axiom");
        final OWLModelManager mm = requireModelManager();
        final OWLOntology ont = resolveOntology(ontId, mm);
        final OWLAxiom parsed = parseManchesterAxiom(ont, mm.getOWLDataFactory(), axiomStr);
        runOnEdt(() -> mm.applyChanges(Collections.<OWLOntologyChange>singletonList(new RemoveAxiom(ont, parsed))));
        ObjectNode r = MAPPER.createObjectNode();
        r.put("axiom", axiomStr);
        r.put("removed", true);
        return r;
    }

    /**
     * Build a Manchester syntax parser configured to resolve short forms
     * against the given ontology's signature.
     */
    private org.semanticweb.owlapi.expression.OWLEntityChecker manchesterEntityChecker(OWLOntology ont) {
        return new org.semanticweb.owlapi.expression.ShortFormEntityChecker(
                new org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter(
                        Collections.singleton(ont), new SimpleShortFormProvider()));
    }

    private OWLAxiom parseManchesterAxiom(OWLOntology ont, OWLDataFactory df, String text) {
        // Instantiate the parser implementation directly to avoid OWLAPI's Guice
        // injector under OSGi, which logs a noisy "No instantiation found for
        // java.util.function.Supplier<OWLOntologyLoaderConfiguration>" error
        // (the binding isn't visible inside the bundle). The direct constructor
        // takes a Supplier<OWLOntologyLoaderConfiguration> and an OWLDataFactory.
        org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser parser = new org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxParserImpl(
                () -> new org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration(),
                df);
        parser.setOWLEntityChecker(manchesterEntityChecker(ont));
        parser.setDefaultOntology(ont);
        parser.setStringToParse(text);
        try {
            return parser.parseAxiom();
        } catch (org.semanticweb.owlapi.manchestersyntax.renderer.ParserException e) {
            throw new McpException(-32602, "Invalid axiom syntax: " + e.getMessage(), null);
        } catch (RuntimeException e) {
            throw new McpException(-32602, "Invalid axiom syntax: " + e.getMessage(), null);
        }
    }

    // -----------------------------------------------------------------------
    // EDT helper
    // -----------------------------------------------------------------------

    /**
     * Execute {@code r} on the Swing EDT and wait for completion. Used by all
     * V1.5 mutation tools to avoid deadlocks between Protégé workspace
     * listeners (which expect to run on the EDT) and the MCP I/O thread.
     *
     * <p>
     * In headless mode (typical for unit tests) or when already on the EDT,
     * the runnable is executed directly.
     */
    private static void runOnEdt(Runnable r) {
        if (java.awt.GraphicsEnvironment.isHeadless()
                || javax.swing.SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        final Throwable[] holder = new Throwable[1];
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    holder[0] = t;
                }
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
        if (holder[0] != null) {
            if (holder[0] instanceof RuntimeException) {
                throw (RuntimeException) holder[0];
            }
            throw new RuntimeException(holder[0]);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the first OWLModelManager found in the running Protege instance.
     * Throws McpException if no Protege workspace is active.
     */
    OWLModelManager requireModelManager() {
        try {
            ProtegeManager pm = ProtegeManager.getInstance();
            if (pm == null) {
                throw new McpException(-32000, "No active Protege workspace", null);
            }
            java.util.List<EditorKit> kits = pm.getEditorKitManager().getEditorKits();
            for (EditorKit kit : kits) {
                if (kit instanceof OWLEditorKit) {
                    return ((OWLEditorKit) kit).getOWLModelManager();
                }
            }
            // No editor kit registered. This typically means kit registration
            // failed during Protege startup (e.g. a Look-and-Feel reflection
            // error on JDK 17+ macOS aborted EditorKitManager.addEditorKit).
            // Surface a more actionable diagnostic.
            ObjectNode data = MAPPER.createObjectNode();
            data.put("editor_kit_count", kits.size());
            data.put("hint", "no editor kit registered – check Protégé startup log "
                    + "(~/.Protege/logs/protege.log) for kit registration failures");
            throw new McpException(-32000, "No active Protege workspace", data);
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            // ProtegeManager backed by an inactive OSGi context (e.g. running outside
            // the Protege Desktop bundle, in tests, or before activation) raises
            // assorted NPE / IllegalStateException variants from getEditorKitManager().
            // Surface a single canonical envelope and stash the underlying cause in
            // `data` for diagnostics rather than leaking it into the user-visible
            // message.
            ObjectNode data = MAPPER.createObjectNode();
            data.put("cause", e.getClass().getName());
            data.put("detail", e.getMessage() == null ? "" : e.getMessage());
            throw new McpException(-32000, "No active Protege workspace", data);
        }
    }

    private OWLOntology resolveOntology(String ontologyId, OWLModelManager mm) {
        for (OWLOntology ont : mm.getOntologies()) {
            if (ontologyIdFor(ont).equals(ontologyId)) {
                return ont;
            }
        }
        throw new McpException(-32010, "Ontology not found: " + ontologyId,
                MAPPER.createObjectNode().put("ontology_id", ontologyId));
    }

    /** Derive a stable string identifier from an ontology's IRI. */
    static String ontologyIdFor(OWLOntology ont) {
        com.google.common.base.Optional<IRI> iri = ont.getOntologyID().getOntologyIRI();
        return iri.isPresent() ? iri.get().toString()
                : "anon:" + System.identityHashCode(ont);
    }

    private OWLReasoner requireReasoner(OWLModelManager mm) {
        OWLReasoner r = mm.getReasoner();
        if (r == null) {
            throw new McpException(-32000, "No active reasoner", null);
        }
        return r;
    }

    /**
     * If {@code dryRun} is true, validates but does not apply changes.
     * If {@code direct} arg is set to {@code true}, applies changes immediately.
     * Otherwise queues axioms in the per-ontology sandbox.
     */
    private void applyOrQueue(String ontId, JsonNode args,
            final List<OWLOntologyChange> changes,
            final OWLModelManager mm, boolean dryRun) {
        if (dryRun)
            return;
        if (args.path("direct").asBoolean(false)) {
            runOnEdt(() -> mm.applyChanges(changes));
        } else {
            List<OWLAxiom> sandbox = sandboxes.computeIfAbsent(ontId, k -> new ArrayList<>());
            for (OWLOntologyChange c : changes) {
                if (c instanceof AddAxiom) {
                    sandbox.add(((AddAxiom) c).getAxiom());
                }
            }
        }
    }

    private static String requireString(JsonNode args, String field) {
        JsonNode node = args.path(field);
        if (node.isMissingNode() || node.isNull() || node.asText("").isEmpty()) {
            throw new McpException(-32602, "Missing required field: " + field,
                    MAPPER.createObjectNode().put("field", field));
        }
        return node.asText();
    }

    private static String firstString(JsonNode args, String... fields) {
        for (String field : fields) {
            JsonNode node = args.path(field);
            if (!node.isMissingNode() && !node.isNull() && !node.asText("").isEmpty()) {
                return node.asText();
            }
        }
        return null;
    }

    private static String firstRequiredString(JsonNode args, String... fields) {
        String value = firstString(args, fields);
        if (value == null || value.isEmpty()) {
            throw new McpException(-32602, "Missing required field",
                    MAPPER.createObjectNode().put("fields", String.join(", ", fields)));
        }
        return value;
    }

    private ObjectNode entityNode(OWLEntity e, OWLModelManager mm) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("iri", e.getIRI().toString());
        n.put("rendering", mm.getRendering(e));
        n.put("type", e.getEntityType().getName());
        return n;
    }

    private ObjectNode classNode(OWLClass c, OWLModelManager mm) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("iri", c.getIRI().toString());
        n.put("rendering", mm.getRendering(c));
        return n;
    }

    // -----------------------------------------------------------------------
    // Static tool schema builders
    // -----------------------------------------------------------------------

    private static List<ObjectNode> buildToolDefs() {
        List<ObjectNode> tools = new ArrayList<>();
        tools.add(tool("server_info",
                "Return server-level metadata: version, transport, and capabilities.",
                schema()));
        tools.add(tool("ontology_list",
                "List all ontologies currently open in the attached Protege workspace.",
                schema()));
        tools.add(tool("ontology_open",
                "Load an ontology from a URI into the attached workspace.",
                schema("source", "string", "Ontology document URI or local file path")));
        tools.add(tool("ontology_close",
                "Close an ontology workspace and discard its sandbox.",
                schema("ontology_id", "string", "Ontology IRI returned by ontology_list or ontology_open")));
        tools.add(tool("ontology_info",
                "Return axiom counts, format, and workspace state for an open ontology.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("ontology_capabilities",
                "Describe write support, reasoner availability, and export formats.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("workspace_status",
                "Inspect dirty state, sandbox size, and lock state.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("entity_search",
                "Search entities by fragment or label fragment; returns IRI, rendering, and type.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "query", "string", "Search string or fragment")));
        tools.add(tool("entity_get",
                "Get structured details and annotations for a single entity by IRI.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "entity_iri", "string", "Absolute entity IRI")));
        tools.add(tool("hierarchy_get",
                "Return asserted parents and children for a class in the class hierarchy.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "entity_iri", "string", "Absolute class IRI")));
        tools.add(tool("axioms_list",
                "List axioms for the ontology or for a specific entity. Supports limit and entity_iri.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("dl_query",
                "Execute a Manchester Syntax DL expression; returns instances or subclasses.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "expression", "string", "Manchester Syntax class expression",
                        "mode", "string", "'instances' (default) or 'subclasses'")));
        tools.add(tool("class_create",
                "Declare a new OWL class, optionally with a parent. Defaults to sandbox.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "class_iri", "string", "IRI for the new class")));
        tools.add(tool("property_create",
                "Declare a new property (object/data/annotation). Defaults to sandbox.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "property_iri", "string", "IRI for the new property",
                        "property_kind", "string", "'object' | 'data' | 'annotation' (default: object)")));
        tools.add(tool("individual_create",
                "Declare a named individual, optionally with an rdf:type. Defaults to sandbox.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "individual_iri", "string", "IRI for the new individual")));
        tools.add(tool("individual_assert_type",
                "Add an rdf:type assertion for an existing individual. Defaults to sandbox.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "individual_iri", "string", "Individual IRI",
                        "class_iri", "string", "Class IRI to assert as type")));
        tools.add(tool("annotation_set",
                "Add an annotation assertion axiom (e.g. rdfs:label). Defaults to sandbox.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "target_iri", "string", "Subject IRI",
                        "property_iri", "string", "Annotation property IRI",
                        "value", "string", "Literal value")));
        tools.add(tool("ontology_validate",
                "Run structural validation checks and report issues.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("consistency_check",
                "Check ontology consistency with the active reasoner.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("reasoner_classify",
                "Run classification and return unsatisfiable class count.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("ontology_save",
                "Persist the ontology to its current physical location.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("ontology_export",
                "Export ontology content as a string in the specified format.",
                schema("ontology_id", "string", "Target ontology IRI",
                        "format", "string", "'Turtle' (default) | 'RDF/XML' | 'Manchester'")));
        tools.add(tool("sandbox_commit",
                "Promote pending sandbox axioms to the live ontology.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("class_delete",
                "Remove all axioms referencing a class (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "class_iri", "string", "Class IRI to delete")));
        tools.add(tool("class_rename",
                "Rename a class IRI across the active ontology (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "old_iri", "string", "Existing class IRI",
                        "new_iri", "string", "New class IRI")));
        tools.add(tool("object_property_create",
                "Declare a new object property, optionally as sub-property of a parent (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "property_iri", "string", "Object property IRI")));
        tools.add(tool("object_property_delete",
                "Remove all axioms referencing an object property (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "property_iri", "string", "Object property IRI")));
        tools.add(tool("object_property_rename",
                "Rename an object property IRI across the active ontology (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "old_iri", "string", "Existing object property IRI",
                        "new_iri", "string", "New object property IRI")));
        tools.add(tool("data_property_create",
                "Declare a new data property, optionally as sub-property of a parent (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "property_iri", "string", "Data property IRI")));
        tools.add(tool("data_property_delete",
                "Remove all axioms referencing a data property (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "property_iri", "string", "Data property IRI")));
        tools.add(tool("data_property_rename",
                "Rename a data property IRI across the active ontology (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "old_iri", "string", "Existing data property IRI",
                        "new_iri", "string", "New data property IRI")));
        tools.add(tool("entity_annotate_set",
                "Set rdfs:label or rdfs:comment on an entity, replacing existing matching annotations (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "entity_iri", "string", "Entity IRI to annotate",
                        "property", "string", "'rdfs:label' or 'rdfs:comment'",
                        "value", "string", "Annotation literal value")));
        tools.add(tool("entity_annotate_remove",
                "Remove rdfs:label or rdfs:comment annotations from an entity (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "entity_iri", "string", "Entity IRI",
                        "property", "string", "'rdfs:label' or 'rdfs:comment'")));
        tools.add(tool("ontology_reload",
                "Force-reload an ontology from its physical URI, discarding in-memory edits and sandbox.",
                schema("ontology_id", "string", "Target ontology IRI")));
        tools.add(tool("individual_delete",
                "Remove all axioms referencing a named individual (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "individual_iri", "string", "Individual IRI to delete")));
        tools.add(tool("individual_rename",
                "Rename a named individual IRI across the active ontology (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "old_iri", "string", "Existing individual IRI",
                        "new_iri", "string", "New individual IRI")));
        tools.add(tool("axiom_add",
                "Parse a Manchester-syntax axiom and add it to the ontology (direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "axiom", "string", "Manchester-syntax axiom string")));
        tools.add(tool("axiom_remove",
                "Parse a Manchester-syntax axiom and remove it from the ontology (idempotent, direct mode).",
                schema("ontology_id", "string", "Target ontology IRI",
                        "axiom", "string", "Manchester-syntax axiom string")));
        return Collections.unmodifiableList(tools);
    }

    private static ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode t = MAPPER.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        t.set("inputSchema", inputSchema);
        return t;
    }

    /** Build an empty schema (object with no required properties). */
    private static ObjectNode schema() {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("type", "object");
        s.putObject("properties");
        return s;
    }

    /** Build a schema with one or more (field, type, description) triples. */
    private static ObjectNode schema(String... fieldTypeDesc) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        ArrayNode required = MAPPER.createArrayNode();
        for (int i = 0; i + 3 <= fieldTypeDesc.length; i += 3) {
            String field = fieldTypeDesc[i];
            ObjectNode fp = props.putObject(field);
            fp.put("type", fieldTypeDesc[i + 1]);
            fp.put("description", fieldTypeDesc[i + 2]);
            required.add(field);
        }
        if (required.size() > 0) {
            s.set("required", required);
        }
        return s;
    }
}
