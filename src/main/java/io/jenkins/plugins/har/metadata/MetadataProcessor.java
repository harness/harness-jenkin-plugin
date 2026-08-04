package io.jenkins.plugins.har.metadata;

import java.io.PrintStream;

/*
    This class can be used to all processing of metadata collected by jenkin run
 */
public class MetadataProcessor {

    public void printMetaDataToRunEnv(PrintStream log, String metadataJson){
        log.println("[harUpload] Build metadata:\n" + metadataJson);
    }
}
