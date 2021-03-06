/*
 * Copyright 2016
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.dkpro.core.opennlp;

import java.util.concurrent.Callable;
import org.apache.uima.fit.descriptor.ConfigurationParameter;

import de.tudarmstadt.ukp.dkpro.core.api.parameter.ComponentParameters;
import de.tudarmstadt.ukp.dkpro.core.opennlp.internal.CasLemmaSampleStream;
import de.tudarmstadt.ukp.dkpro.core.opennlp.internal.OpenNlpTrainerBase;
import opennlp.tools.lemmatizer.LemmatizerFactory;
import opennlp.tools.lemmatizer.LemmatizerME;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.ml.EventTrainer;
import opennlp.tools.util.TrainingParameters;

/**
 * Train a lemmatizer model for OpenNLP.
 */
public class OpenNlpLemmatizerTrainer
    extends OpenNlpTrainerBase<CasLemmaSampleStream>
{
    public static final String PARAM_LANGUAGE = ComponentParameters.PARAM_LANGUAGE;
    @ConfigurationParameter(name = PARAM_LANGUAGE, mandatory = true)
    private String language;

    public static final String PARAM_ALGORITHM = "algorithm";
    @ConfigurationParameter(name = PARAM_ALGORITHM, mandatory = true, defaultValue = "MAXENT")
    private String algorithm;
    
    public static final String PARAM_TRAINER_TYPE = "trainerType";
    @ConfigurationParameter(name = PARAM_TRAINER_TYPE, mandatory = true, defaultValue = EventTrainer.EVENT_VALUE)
    private String trainerType;

    public static final String PARAM_ITERATIONS = "iterations";
    @ConfigurationParameter(name = PARAM_ITERATIONS, mandatory = true, defaultValue = "100")
    private int iterations;

    public static final String PARAM_CUTOFF = "cutoff";
    @ConfigurationParameter(name = PARAM_CUTOFF, mandatory = true, defaultValue = "5")
    private int cutoff;

    @Override
    public CasLemmaSampleStream makeSampleStream()
    {
        return new CasLemmaSampleStream();
    }
    
    @Override
    public Callable<? extends LemmatizerModel> makeTrainer()
    {
        TrainingParameters params = new TrainingParameters();
        params.put(TrainingParameters.ALGORITHM_PARAM, algorithm);
        params.put(TrainingParameters.TRAINER_TYPE_PARAM, trainerType);
        params.put(TrainingParameters.ITERATIONS_PARAM, Integer.toString(iterations));
        params.put(TrainingParameters.CUTOFF_PARAM, Integer.toString(cutoff));
        
        Callable<LemmatizerModel> trainTask = () -> {
            try {
                return LemmatizerME.train(language, getStream(), params, new LemmatizerFactory());
            }
            catch (Throwable e) {
                getStream().close();
                throw e;
            }
        };
        
        return trainTask;
    }
}
