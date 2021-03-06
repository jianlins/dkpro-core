#!/usr/bin/env groovy

import static groovy.io.FileType.FILES;
import groovy.json.*;
import groovy.transform.Field;

import static org.apache.uima.fit.factory.ResourceCreationSpecifierFactory.*;

import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.collection.CollectionReaderDescription;

@Field def engines = [:];

@Field def formats = [:];

def locatePom(path) {
    def pom = new File(path, "pom.xml");
    if (pom.exists()) {
        return pom;
    }
    else if (path.getParentFile() != null) {
        return locatePom(path.getParentFile());
    }
    else {
        return null;
    }
}

def addFormat(format, kind, pom, clazz) {
    if (!formats[format]) {
        formats[format] = [
            groupId: pom.groupId ? pom.groupId.text() : pom.parent.groupId.text(),
            artifactId: pom.artifactId.text(),
            version: pom.version ? pom.version.text() : pom.parent.version.text(),
        ];
    }
    formats[format][kind] = clazz;
}

def processUimaDescriptor(File aDescriptorFile)
{
    def spec = createResourceCreationSpecifier(aDescriptorFile.path, null);
    if (spec instanceof AnalysisEngineDescription) {
        // println "AE " + it;
        def implName = spec.annotatorImplementationName;
        def uniqueName = implName.substring(implName.lastIndexOf('.')+1);
        def pomFile = locatePom(aDescriptorFile);
        def pom = new XmlParser().parse(pomFile);

        if (!implName.contains('$')) {
            if (implName.endsWith('Writer')) {
                def format = uniqueName[0..-7];
                addFormat(format, 'writerClass', pom, spec.annotatorImplementationName);
            }
            else {
                engines[uniqueName] = [
                    name: uniqueName,
                    groupId: pom.groupId ? pom.groupId.text() : pom.parent.groupId.text(),
                    artifactId: pom.artifactId.text(),
                    version: pom.version ? pom.version.text() : pom.parent.version.text(),
                    class:  spec.annotatorImplementationName
                ];
            }
        }
    }
    else if (spec instanceof CollectionReaderDescription) {
        // println "CR " + it;
        def implName = spec.implementationName;
        if (implName.endsWith('Reader') && !implName.contains('$')) {
            def uniqueName = implName.substring(implName.lastIndexOf('.')+1);
            def pomFile = locatePom(aDescriptorFile);
            def pom = new XmlParser().parse(pomFile);
            def format = uniqueName[0..-7];
            addFormat(format, 'readerClass', pom, implName);
        }
    }
    else {
        // println "?? " + it;
    }
}

new File(baseDir).eachFileRecurse(FILES) {
    if(it.name.endsWith('.xml') && !it.path.contains("src/test/java")) {
        try {
            processUimaDescriptor(it);
        }
        catch (org.apache.uima.util.InvalidXMLException e) {
            // Ignore
        }
    }
}

//println JsonOutput.prettyPrint(JsonOutput.toJson(engines));

println JsonOutput.prettyPrint(JsonOutput.toJson(formats));