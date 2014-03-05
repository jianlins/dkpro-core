/**
 * Copyright 2013
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.tudarmstadt.ukp.dkpro.core.stanfordnlp;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.uima.util.Level.FINE;
import static org.apache.uima.util.Level.INFO;
import static org.apache.uima.util.Level.WARNING;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CASException;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.Type;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Level;

import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;
import de.tudarmstadt.ukp.dkpro.core.api.metadata.SingletonTagset;
import de.tudarmstadt.ukp.dkpro.core.api.parameter.ComponentParameters;
import de.tudarmstadt.ukp.dkpro.core.api.resources.CasConfigurableProviderBase;
import de.tudarmstadt.ukp.dkpro.core.api.resources.MappingProvider;
import de.tudarmstadt.ukp.dkpro.core.api.resources.ModelProviderBase;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.constituent.Constituent;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency;
import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.util.StanfordAnnotator;
import de.tudarmstadt.ukp.dkpro.core.stanfordnlp.util.TreeWithTokens;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.parser.lexparser.ParserQuery;
import edu.stanford.nlp.process.PTBEscapingProcessor;
import edu.stanford.nlp.trees.AbstractTreebankLanguagePack;
import edu.stanford.nlp.trees.EnglishGrammaticalRelations;
import edu.stanford.nlp.trees.EnglishGrammaticalStructureFactory;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.trees.GrammaticalStructure;
import edu.stanford.nlp.trees.GrammaticalStructureFactory;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TypedDependency;
import edu.stanford.nlp.trees.international.pennchinese.ChineseGrammaticalRelations;

/**
 * Stanford Parser component.
 *
 * @author Oliver Ferschke
 * @author Niklas Jakob
 */
public class StanfordParser
    extends JCasAnnotator_ImplBase
{
    public static enum DependenciesMode {
        /**
         * Produce basic dependencies. <br>
         * Corresponding parser option: {@code basic}
         */
        BASIC,                  // basic        - typedDependencies(false)
        /**
         * Produce basic dependencies plus extra arcs for control relationships, etc. <br>
         * Corresponding parser option: {@code nonCollapsed}
         */
        NON_COLLAPSED,          // nonCollapsed - typedDependencies(true)
        /**
         * Produce collapsed dependencies. This removes dependencies on specific function words
         * (e.g. prepositions and conjunctions). The result not be a tree, e.g. it can include
         * cycles and re-entrancies. <br>
         * Corresponding parser option: {@code collapsed}
         */
        COLLAPSED,              // collapsed     - typedDependenciesCollapsed(false)
        /**
         * Produce collapsed dependencies plus extra arcs for control relationships, etc. <br>
         * Corresponding parser option: {@code not available}
         */
        COLLAPSED_WITH_EXTRA,   // - none -     - typedDependenciesCollapsed(true)
        /**
         * Produce collapsed dependencies plus extra arcs for control relationships, etc.
         * In this mode, depencendies are collapsed across coordination. This mode is supposed to
         * produce the best syntactic and semantic representation of a sentence. The result
         * may not be a tree (may contain cycles), but is a directed graph.<br>
         * Corresponding parser option: {@code CCPropagated}
         */
         CC_PROPAGATED,          // CCPropagated - typedDependenciesCCprocessed(true)
        /**
         * Produce dependencies collapsed across coordination. No extra dependencies for control
         * relations are included.<br>
         * Corresponding parser option: {@code not available}
         */
         CC_PROPAGATED_NO_EXTRA, // - none -     - typedDependenciesCCprocessed(false)
        /**
         * Produce mostly collapsed dependencies that remain a tree structure. Several steps are
         * omitted:
         * <ol>
         * <li>no processing of relative clauses</li>
         * <li>no xsubj relations</li>
         * <li>no propagation of conjuncts</lu>
         * </ol>
         * Corresponding parser option: {@code tree}
         */
         TREE                    // tree         - typedDependencies(false) + collapseDependenciesTree(tdl)
    }
    
    /**
     * Write the tag set(s) to the log when a model is loaded.
     */
    public static final String PARAM_PRINT_TAGSET = ComponentParameters.PARAM_PRINT_TAGSET;
    @ConfigurationParameter(name = PARAM_PRINT_TAGSET, mandatory = true, defaultValue = "false")
    protected boolean printTagSet;

    /**
     * Use this language instead of the document language to resolve the model and tag set mapping.
     */
    public static final String PARAM_LANGUAGE = ComponentParameters.PARAM_LANGUAGE;
    @ConfigurationParameter(name = PARAM_LANGUAGE, mandatory = false)
    protected String language;

    /**
     * Variant of a model the model. Used to address a specific model if here are multiple models
     * for one language.
     */
    public static final String PARAM_VARIANT = ComponentParameters.PARAM_VARIANT;
    @ConfigurationParameter(name = PARAM_VARIANT, mandatory = false)
    protected String variant;

    /**
     * Location from which the model is read.
     */
    public static final String PARAM_MODEL_LOCATION = ComponentParameters.PARAM_MODEL_LOCATION;
    @ConfigurationParameter(name = PARAM_MODEL_LOCATION, mandatory = false)
    protected String modelLocation;

    /**
     * Location of the mapping file for part-of-speech tags to UIMA types.
     */
    public static final String PARAM_POS_MAPPING_LOCATION = ComponentParameters.PARAM_POS_MAPPING_LOCATION;
    @ConfigurationParameter(name = PARAM_POS_MAPPING_LOCATION, mandatory = false)
    protected String posMappingLocation;

    /**
     * Sets whether to create or not to create dependency annotations. <br/>
     * 
     * Default: {@code true}
     */
    public static final String PARAM_WRITE_DEPENDENCY = ComponentParameters.PARAM_WRITE_DEPENDENCY;
    @ConfigurationParameter(name = PARAM_WRITE_DEPENDENCY, mandatory = true, defaultValue = "true")
    private boolean writeDependency;

    /**
     * Sets the kind of dependencies being created. <br/>
     * 
     * Default: {@link DependenciesMode#COLLAPSED TREE}
     * @see DependenciesMode
     */
    public static final String PARAM_MODE = "mode";
    @ConfigurationParameter(name = PARAM_MODE, mandatory = false, defaultValue = "TREE")
    protected DependenciesMode mode;
    
    /**
     * Sets whether to create or not to create constituent tags. This is required for POS-tagging
     * and lemmatization.<br/>
     * 
     * Default: {@code true}
     */
    public static final String PARAM_WRITE_CONSTITUENT = ComponentParameters.PARAM_WRITE_CONSTITUENT;
    @ConfigurationParameter(name = PARAM_WRITE_CONSTITUENT, mandatory = true, defaultValue = "true")
    private boolean writeConstituent;

    /**
     * If this parameter is set to true, each sentence is annotated with a PennTree-Annotation,
     * containing the whole parse tree in Penn Treebank style format.<br/>
     * 
     * Default: {@code false}
     */
    public static final String PARAM_WRITE_PENN_TREE = ComponentParameters.PARAM_WRITE_PENN_TREE;
    @ConfigurationParameter(name = PARAM_WRITE_PENN_TREE, mandatory = true, defaultValue = "false")
    private boolean writePennTree;

    /**
     * This parameter can be used to override the standard behavior which uses the <i>Sentence</i>
     * annotation as the basic unit for parsing.<br/>
     * If the parameter is set with the name of an annotation type <i>x</i>, the parser will no
     * longer parse <i>Sentence</i>-annotations, but <i>x</i>-Annotations.<br/>
     * Default: {@code null}
     */
    public static final String PARAM_ANNOTATIONTYPE_TO_PARSE = "annotationTypeToParse";
    @ConfigurationParameter(name = PARAM_ANNOTATIONTYPE_TO_PARSE, mandatory = false)
    private String annotationTypeToParse;

    /**
     * Sets whether to create or not to create POS tags. The creation of constituent tags must be
     * turned on for this to work.<br/>
     * Default: {@code true}
     */
    public static final String PARAM_WRITE_POS = ComponentParameters.PARAM_WRITE_POS;
    @ConfigurationParameter(name = PARAM_WRITE_POS, mandatory = true, defaultValue = "true")
    private boolean writePos;

    /**
     * Sets whether to use or not to use already existing POS tags from another annotator for the
     * parsing process.<br/>
     * Default: {@code false}
     */
    public static final String PARAM_READ_POS = ComponentParameters.PARAM_READ_POS;
    @ConfigurationParameter(name = PARAM_READ_POS, mandatory = true, defaultValue = "false")
    private boolean readPos;

    /**
     * Maximum number of tokens in a sentence. Longer sentences are not parsed. This is to avoid out
     * of memory exceptions.<br/>
     * Default: {@code 130}
     */
    public static final String PARAM_MAX_TOKENS = "maxTokens";
    @ConfigurationParameter(name = PARAM_MAX_TOKENS, mandatory = true, defaultValue = "130")
    private int maxTokens;

    /**
     * Enable all traditional PTB3 token transforms (like -LRB-, -RRB-).
     *
     * @see PTBEscapingProcessor
     */
    public static final String PARAM_PTB3_ESCAPING = "ptb3Escaping";
    @ConfigurationParameter(name = PARAM_PTB3_ESCAPING, mandatory = true, defaultValue = "true")
    private boolean ptb3Escaping;

    /**
     * List of extra token texts (usually single character strings) that should be treated like
     * opening quotes and escaped accordingly before being sent to the parser.
     */
    public static final String PARAM_QUOTE_BEGIN = "quoteBegin";
    @ConfigurationParameter(name = PARAM_QUOTE_BEGIN, mandatory = false)
    private List<String> quoteBegin;

    /**
     * List of extra token texts (usually single character strings) that should be treated like
     * closing quotes and escaped accordingly before being sent to the parser.
     */
    public static final String PARAM_QUOTE_END = "quoteEnd";
    @ConfigurationParameter(name = PARAM_QUOTE_END, mandatory = false)
    private List<String> quoteEnd;

    private GrammaticalStructureFactory gsf;

    private CasConfigurableProviderBase<LexicalizedParser> modelProvider;
    private MappingProvider posMappingProvider;

    private final PTBEscapingProcessor<HasWord, String, Word> escaper = new PTBEscapingProcessor<HasWord, String, Word>();

    @Override
    public void initialize(UimaContext context)
        throws ResourceInitializationException
    {
        super.initialize(context);

        if (!writeConstituent && !writeDependency && !writePennTree) {
            getLogger().warn("Invalid parameter configuration... will create dependency tags.");
            writeDependency = true;
        }

        // Check if we want to create Lemmas or POS tags while Consituent tags
        // are disabled. In this case, we have to switch on constituent tagging
        if (!writeConstituent && writePos) {
            getLogger().warn("Constituent tag creation is required for POS tagging. Will create "
                    + "constituent tags.");
            writeConstituent = true;
        }

        modelProvider = new StanfordParserModelProvider();

        posMappingProvider = new MappingProvider();
        posMappingProvider.setDefault(MappingProvider.LOCATION,
                "classpath:/de/tudarmstadt/ukp/dkpro/"
                        + "core/api/lexmorph/tagset/${language}-${pos.tagset}-pos.map");
        posMappingProvider.setDefault(MappingProvider.BASE_TYPE, POS.class.getName());
        posMappingProvider.setDefault("pos.tagset", "default");
        posMappingProvider.setOverride(MappingProvider.LOCATION, posMappingLocation);
        posMappingProvider.setOverride(MappingProvider.LANGUAGE, language);
        posMappingProvider.addImport("pos.tagset", modelProvider);
    }

    /**
     * Processes the given text using the StanfordParser.
     *
     * @param aJCas
     *            the {@link JCas} to process
     * @see org.apache.uima.analysis_component.JCasAnnotator_ImplBase#process(org.apache.uima.jcas.JCas)
     */
    @Override
    public void process(JCas aJCas)
        throws AnalysisEngineProcessException
    {
        modelProvider.configure(aJCas.getCas());
        posMappingProvider.configure(aJCas.getCas());

        Type typeToParse;
        if (annotationTypeToParse != null) {
            typeToParse = aJCas.getCas().getTypeSystem().getType(annotationTypeToParse);
        }
        else {
            typeToParse = JCasUtil.getType(aJCas, Sentence.class);
        }
        FSIterator<Annotation> typeToParseIterator = aJCas.getAnnotationIndex(typeToParse)
                .iterator();

        // Iterator each Sentence or whichever construct to parse

        while (typeToParseIterator.hasNext()) {
            Annotation currAnnotationToParse = typeToParseIterator.next();
            List<HasWord> tokenizedSentence = new ArrayList<HasWord>();
            List<Token> tokens = new ArrayList<Token>();

            // Split sentence to tokens for annotating indexes
            for (Token token : JCasUtil.selectCovered(Token.class, currAnnotationToParse)) {
                tokenizedSentence.add(tokenToWord(token));
                tokens.add(token);
            }

            getContext().getLogger().log(FINE, tokenizedSentence.toString());
            LexicalizedParser parser = modelProvider.getResource();

            Tree parseTree;
            try {
                if (tokenizedSentence.size() <= maxTokens) {
                    if (ptb3Escaping) {
                        // Apply escaper to the whole sentence, not to each token individually. The
                        // escaper takes context into account, e.g. when transforming regular double
                        // quotes into PTB opening and closing quotes (`` and '').
                        tokenizedSentence = escaper.apply(tokenizedSentence);
                        for (HasWord w : tokenizedSentence) {
                            if (quoteBegin != null && quoteBegin.contains(w.word())) {
                                w.setWord("``");
                            }
                            else if (quoteEnd != null && quoteEnd.contains(w.word())) {
                                w.setWord("\'\'");
                            }
                        }
                    }

                    // Get parse
                    ParserQuery query = parser.parserQuery();
                    query.parse(tokenizedSentence);
                    parseTree = query.getBestParse();
                }
                else {
                    continue;
                }

            }
            catch (Exception e) {
                throw new AnalysisEngineProcessException(e);
            }

            // Create new StanfordAnnotator object
            StanfordAnnotator sfAnnotator = null;
            try {
                sfAnnotator = new StanfordAnnotator(new TreeWithTokens(parseTree, tokens));
                sfAnnotator.setPosMappingProvider(posMappingProvider);
            }
            catch (CASException e) {
                throw new AnalysisEngineProcessException(e);
            }

            // Create Penn bracketed structure annotations
            if (writePennTree) {
                sfAnnotator.createPennTreeAnnotation(currAnnotationToParse.getBegin(),
                        currAnnotationToParse.getEnd());
            }

            // Create dependency annotations
            if (writeDependency && gsf != null) {
                doCreateDependencyTags(sfAnnotator, parseTree, tokens);
            }

            // Create constituent annotations
            if (writeConstituent) {
                sfAnnotator.createConstituentAnnotationFromTree(parser.getTLPParams()
                        .treebankLanguagePack(), writePos);
            }
        }
    }

    protected void doCreateDependencyTags(StanfordAnnotator sfAnnotator, Tree parseTree,
            List<Token> tokens)
    {
        GrammaticalStructure gs = gsf.newGrammaticalStructure(parseTree);
        Collection<TypedDependency> dependencies = null;
        switch (mode) {
        case BASIC:
            dependencies = gs.typedDependencies(); // gs.typedDependencies(false);
            break;
        case NON_COLLAPSED:
            dependencies = gs.allTypedDependencies(); // gs.typedDependencies(true);
            break;
        case COLLAPSED_WITH_EXTRA:
            dependencies = gs.typedDependenciesCollapsed(true);
            break;
        case COLLAPSED:
            dependencies = gs.typedDependenciesCollapsed(false);
            break;
        case CC_PROPAGATED:
            dependencies = gs.typedDependenciesCCprocessed(true);
            break;
        case CC_PROPAGATED_NO_EXTRA:
            dependencies = gs.typedDependenciesCCprocessed(false);
            break;
        case TREE:
            dependencies = gs.typedDependenciesCollapsedTree();
            break;
        }

        for (TypedDependency currTypedDep : dependencies) {
            int govIndex = currTypedDep.gov().index();
            int depIndex = currTypedDep.dep().index();
            if (govIndex != 0) {
                // Stanford CoreNLP produces a dependency relation between a verb and ROOT-0 which
                // is not token at all!
                Token govToken = tokens.get(govIndex - 1);
                Token depToken = tokens.get(depIndex - 1);

                sfAnnotator.createDependencyAnnotation(currTypedDep.reln(), govToken, depToken);
            }
        }
    }

    protected CoreLabel tokenToWord(Token aToken)
    {
        CoreLabel tw = new CoreLabel();
        tw.setValue(aToken.getCoveredText());
        tw.setWord(aToken.getCoveredText());
        tw.setBeginPosition(aToken.getBegin());
        tw.setEndPosition(aToken.getEnd());

        if (readPos && aToken.getPos() != null) {
            String posValue = aToken.getPos().getPosValue();
            tw.setTag(posValue);
        }

        return tw;
    }

    private class StanfordParserModelProvider
        extends ModelProviderBase<LexicalizedParser>
    {
        {
            setContextObject(StanfordParser.this);

            setDefault(ARTIFACT_ID, "${groupId}.stanfordnlp-model-parser-${language}-${variant}");
            setDefault(LOCATION, "classpath:/${package}/lib/parser-${language}-${variant}.properties");
            setDefaultVariantsLocation("${package}/lib/parser-default-variants.map");

            setOverride(LOCATION, modelLocation);
            setOverride(LANGUAGE, language);
            setOverride(VARIANT, variant);
        }

        @Override
        protected LexicalizedParser produceResource(URL aUrl)
            throws IOException
        {
            getContext().getLogger().log(Level.INFO,
                    "Loading parser from serialized file " + aUrl + " ...");
            ObjectInputStream in = null;
            InputStream is = null;
            try {
                is = aUrl.openStream();

                if (aUrl.toString().endsWith(".gz")) {
                    // it's faster to do the buffering _outside_ the gzipping as here
                    in = new ObjectInputStream(new BufferedInputStream(new GZIPInputStream(is)));
                }
                else {
                    in = new ObjectInputStream(new BufferedInputStream(is));
                }
                LexicalizedParser pd = (LexicalizedParser) in.readObject();
                AbstractTreebankLanguagePack lp = (AbstractTreebankLanguagePack) pd.getTLPParams()
                        .treebankLanguagePack();
                try {
                    gsf = lp.grammaticalStructureFactory(lp.punctuationWordRejectFilter(),
                            lp.typedDependencyHeadFinder());
                }
                catch (UnsupportedOperationException e) {
                    getContext().getLogger().log(WARNING,
                            "Current model does not seem to support " + "dependencies.");
                    gsf = null;
                }

                Properties metadata = getResourceMetaData();

                // https://mailman.stanford.edu/pipermail/parser-user/2012-November/002117.html
                // The tagIndex does give all and only the set of POS tags used in the
                // current grammar. However, these are the split tags actually used by the
                // grammar. If you really want the user-visible non-split tags of the
                // original treebank, then you'd need to map them all through the
                // op.treebankLanguagePack().basicCategory(). -- C. Manning
                SingletonTagset posTags = new SingletonTagset(
                        POS.class, metadata.getProperty("pos.tagset"));
                for (String tag : pd.tagIndex) {
                    String t = lp.basicCategory(tag);

                    // Strip grammatical function from tag
                    int gfIdx = t.indexOf(lp.getGfCharacter());
                    if (gfIdx > 0) {
                        // TODO should collect syntactic functions in separate tagset
                        // syntacticFunction = nodeLabelValue.substring(gfIdx + 1);
                        t = t.substring(0, gfIdx);
                    }
                    posTags.add(lp.basicCategory(t));
                }
                addTagset(posTags, writePos);

                // https://mailman.stanford.edu/pipermail/parser-user/2012-November/002117.html
                // For constituent categories, there isn't an index of just them. The
                // stateIndex has both constituent categories and POS tags in it, so you'd
                // need to set difference out the tags from the tagIndex, and then it's as
                // above. -- C. Manning
                SingletonTagset constTags = new SingletonTagset(
                        Constituent.class, metadata.getProperty("constituent.tagset"));
                for (String tag : pd.stateIndex) {
                    String t = lp.basicCategory(tag);
                    // https://mailman.stanford.edu/pipermail/parser-user/2012-December/002156.html
                    // The parser algorithm used is a binary parser, so what we do is
                    // binarize trees by turning A -> B, C, D into A -> B, @A, @A -> C, D.
                    // (That's roughly how it goes, although the exact details are somewhat
                    // different.) When parsing, we parse to a binarized tree and then
                    // unbinarize it before returning. That's the origin of the @ classes.
                    // -- J. Bauer
                    if (!t.startsWith("@")) {

                        // Strip grammatical function from tag
                        int gfIdx = t.indexOf(lp.getGfCharacter());
                        if (gfIdx > 0) {
                            // TODO should collect syntactic functions in separate tagset
                            // syntacticFunction = nodeLabelValue.substring(gfIdx + 1);
                            t = t.substring(0, gfIdx);
                        }

                        if (t.length() > 0) {
                            constTags.add(t);
                        }
                    }
                }
                constTags.removeAll(posTags);
                addTagset(constTags, writeConstituent);

                // There is no way to determine the relations via the GrammaticalStructureFactory
                // API, so we do it manually here for the languages known to support this.
                if (gsf != null && EnglishGrammaticalStructureFactory.class.equals(gsf.getClass())) {
                    SingletonTagset depTags = new SingletonTagset(Dependency.class, "stanford331");
                    for (GrammaticalRelation r : EnglishGrammaticalRelations.values()) {
                        depTags.add(r.getShortName());
                    }
                    addTagset(depTags, writeDependency);
                }
                else if (gsf != null && ChineseGrammaticalRelations.class.equals(gsf.getClass())) {
                    SingletonTagset depTags = new SingletonTagset(Dependency.class, "stanford");
                    for (GrammaticalRelation r : ChineseGrammaticalRelations.values()) {
                        depTags.add(r.getShortName());
                    }
                    addTagset(depTags, writeDependency);
                }

                if (printTagSet) {
                    getContext().getLogger().log(INFO, getTagset().toString());
                }

                pd.setOptionFlags("-maxLength", String.valueOf(maxTokens));
                return pd;
            }
            catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
            finally {
                closeQuietly(in);
                closeQuietly(is);
            }
        }
    };
}