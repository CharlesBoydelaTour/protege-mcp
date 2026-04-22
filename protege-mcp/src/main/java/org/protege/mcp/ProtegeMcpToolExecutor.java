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
import org.semanticweb.owlapi.util.SimpleShortFormProvider;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Concrete MCP tool executor that bridges JSON-RPC tool calls to the live
 * Protege OWL model manager.  One instance is created per OSGi bundle
 * activation and shared across all MCP sessions.
 *
 * <p>Sandbox model: each workspace (identified by its ontology IRI string)
 * owns at most one sandbox – an ordered list of pending {@link AddAxiom}
 * changes.  Write tools accumulate changes in the sandbox by default.
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

        public int getCode() { return code; }
        public JsonNode getData() { return data; }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION = "0.1.0";

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
            case "server_info":           return serverInfo();
            case "ontology_list":         return ontologyList();
            case "ontology_open":         return ontologyOpen(args);
            case "ontology_close":        return ontologyClose(args);
            case "ontology_info":         return ontologyInfo(args);
            case "ontology_capabilities": return ontologyCapabilities(args);
            case "workspace_status":      return workspaceStatus(args);
            case "entity_search":         return entitySearch(args);
            case "entity_get":            return entityGet(args);
            case "hierarchy_get":         return hierarchyGet(args);
            case "axioms_list":           return axiomsList(args);
            case "dl_query":              return dlQuery(args);
            case "class_create":          return classCreate(args);
            case "property_create":       return propertyCreate(args);
            case "individual_create":     return individualCreate(args);
            case "individual_assert_type":return individualAssertType(args);
            case "annotation_set":        return annotationSet(args);
            case "ontology_validate":     return ontologyValidate(args);
            case "consistency_check":     return consistencyCheck(args);
            case "reasoner_classify":     return reasonerClassify(args);
            case "ontology_save":         return ontologySave(args);
            case "ontology_export":       return ontologyExport(args);
            case "sandbox_commit":        return sandboxCommit(args);
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
        r.put("version", VERSION);
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
        String uri = requireString(args, "uri");
        OWLModelManager mm = requireModelManager();
        try {
            IRI iri = IRI.create(uri);
            OWLOntology ont = mm.getOWLOntologyManager().loadOntologyFromOntologyDocument(iri);
            ObjectNode r = MAPPER.createObjectNode();
            r.put("ontology_id", ontologyIdFor(ont));
            r.put("axiom_count", ont.getAxiomCount());
            r.put("loaded", true);
            return r;
        } catch (OWLOntologyAlreadyExistsException e) {
            OWLOntology ont = mm.getOWLOntologyManager().getOntology(e.getOntologyID());
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
        String query  = requireString(args, "query");
        OWLModelManager mm = requireModelManager();
        resolveOntology(ontId, mm); // validate
        Set<OWLEntity> matches = mm.getOWLEntityFinder().getMatchingOWLEntities(query);
        int limit = args.path("limit").asInt(50);
        ArrayNode items = MAPPER.createArrayNode();
        int count = 0;
        for (OWLEntity e : matches) {
            if (count++ >= limit) break;
            items.add(entityNode(e, mm));
        }
        ObjectNode r = MAPPER.createObjectNode();
        r.set("entities", items);
        r.put("total", matches.size());
        return r;
    }

    private JsonNode entityGet(JsonNode args) {
        String ontId   = requireString(args, "ontology_id");
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
        String ontId   = requireString(args, "ontology_id");
        String classIri = requireString(args, "class_iri");
        OWLModelManager mm = requireModelManager();
        resolveOntology(ontId, mm);
        IRI iri = IRI.create(classIri);
        OWLClass cls = mm.getOWLDataFactory().getOWLClass(iri);
        var hp = mm.getOWLHierarchyManager().getOWLClassHierarchyProvider();
        ObjectNode r = MAPPER.createObjectNode();
        r.put("class_iri", classIri);
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
                axiomSet.addAll(ont.getAxioms(e));
            }
        } else {
            axiomSet = ont.getAxioms();
        }

        ArrayNode items = MAPPER.createArrayNode();
        int count = 0;
        for (OWLAxiom ax : axiomSet) {
            if (count++ >= limit) break;
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
        String ontId  = requireString(args, "ontology_id");
        String expr   = requireString(args, "expression");
        String mode   = args.path("mode").asText("instances");
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
        String ontId    = requireString(args, "ontology_id");
        String classIri = requireString(args, "class_iri");
        boolean dryRun  = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));

        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(cls)));

        if (args.has("parent_iri") && !args.path("parent_iri").asText("").isEmpty()) {
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
        String ontId    = requireString(args, "ontology_id");
        String propIri  = requireString(args, "property_iri");
        String kind     = args.path("kind").asText("object");
        boolean dryRun  = args.path("dry_run").asBoolean(false);
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
        String ontId   = requireString(args, "ontology_id");
        String indIri  = requireString(args, "individual_iri");
        boolean dryRun = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(indIri));

        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(ont, df.getOWLDeclarationAxiom(ind)));

        if (args.has("type_iri") && !args.path("type_iri").asText("").isEmpty()) {
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
        String ontId   = requireString(args, "ontology_id");
        String indIri  = requireString(args, "individual_iri");
        String typeIri = requireString(args, "type_iri");
        boolean dryRun = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();

        OWLNamedIndividual ind  = df.getOWLNamedIndividual(IRI.create(indIri));
        OWLClass           type = df.getOWLClass(IRI.create(typeIri));
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
        String ontId    = requireString(args, "ontology_id");
        String subjIri  = requireString(args, "subject_iri");
        String propIri  = requireString(args, "property_iri");
        String value    = requireString(args, "value");
        boolean dryRun  = args.path("dry_run").asBoolean(false);
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);
        OWLDataFactory df = mm.getOWLDataFactory();

        OWLAnnotationProperty prop    = df.getOWLAnnotationProperty(IRI.create(propIri));
        OWLAnnotationValue     annVal = df.getOWLLiteral(value);
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
        if (ont.getOntologyID().getVersionIRI().isPresent()) {
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
        String ontId  = requireString(args, "ontology_id");
        String format = args.path("format").asText("Turtle");
        OWLModelManager mm = requireModelManager();
        OWLOntology ont = resolveOntology(ontId, mm);

        OWLDocumentFormat docFormat;
        switch (format.toLowerCase()) {
            case "rdf/xml": case "rdfxml": docFormat = new RDFXMLDocumentFormat(); break;
            case "manchester": docFormat = new ManchesterSyntaxDocumentFormat(); break;
            default: docFormat = new TurtleDocumentFormat();
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
            List<OWLOntologyChange> changes = pending.stream()
                    .map(ax -> (OWLOntologyChange) new AddAxiom(ont, ax))
                    .collect(Collectors.toList());
            mm.applyChanges(changes);
            sandboxes.remove(ontId);
        }

        ObjectNode r = MAPPER.createObjectNode();
        r.put("ontology_id", ontId);
        r.put("committed_changes", pending.size());
        r.put("dry_run", dryRun);
        return r;
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
            for (EditorKit kit : pm.getEditorKitManager().getEditorKits()) {
                if (kit instanceof OWLEditorKit) {
                    return ((OWLEditorKit) kit).getOWLModelManager();
                }
            }
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException(-32000, "Protege workspace unavailable: " + e.getMessage(), null);
        }
        throw new McpException(-32000, "No active Protege workspace", null);
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
                              List<OWLOntologyChange> changes,
                              OWLModelManager mm, boolean dryRun) {
        if (dryRun) return;
        if (args.path("direct").asBoolean(false)) {
            mm.applyChanges(changes);
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
                schema("uri", "string", "Ontology document URI (file: or http:)")));
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
                       "class_iri", "string", "Absolute class IRI")));
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
                       "kind", "string", "'object' | 'data' | 'annotation' (default: object)")));
        tools.add(tool("individual_create",
                "Declare a named individual, optionally with an rdf:type. Defaults to sandbox.",
                schema("ontology_id", "string", "Target ontology IRI",
                       "individual_iri", "string", "IRI for the new individual")));
        tools.add(tool("individual_assert_type",
                "Add an rdf:type assertion for an existing individual. Defaults to sandbox.",
                schema("ontology_id", "string", "Target ontology IRI",
                       "individual_iri", "string", "Individual IRI",
                       "type_iri", "string", "Class IRI to assert as type")));
        tools.add(tool("annotation_set",
                "Add an annotation assertion axiom (e.g. rdfs:label). Defaults to sandbox.",
                schema("ontology_id", "string", "Target ontology IRI",
                       "subject_iri", "string", "Subject IRI",
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
