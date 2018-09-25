package com.redhat.fuse

import org.yaml.snakeyaml.Yaml
//import org.jboss.prod.preProcessYaml
import java.nio.file.Paths
import java.nio.file.Path
import java.util.regex.Pattern
import groovy.util.logging.Slf4j
import java.net.URLDecoder
import groovy.json.JsonOutput.*

/* BuildConfigSection yaml */
@Slf4j
class BuildConfigSection {

    private final ArrayList parsedSection
    private final String rawSection
    
    /* Steps
    1) Load/parse yaml object
    2) Preparse commented sections
    3) Magic (functions for common ops)
    4) Add to parsed yaml
    */

    BuildConfigSection(String rawSection)
    {
        this.rawSection = rawSection
        def sectionHeader = (rawSection.split("\n")[0]).replace("- name:  ", "")
        log.info("Parsing build section: $sectionHeader")
        def yml = new Yaml()
        parsedSection = yml.load(this.rawSection)
    }

    public ArrayList getOriginal()
    {
        return parsedSection
    }
    public String decodeURLs(String encodedURL)
    {
        return URLDecoder.decode(encodedURL, "UTF-8");
    }

    public ArrayList getExtrasAsArray()
    {
        return new Yaml().load(getExtras())
    }

    public String getExtras()
    {
        def stripped = rawSection.replaceAll("([\\s]{4}#)", "  ")
        log.debug("Merging commented fields and reparsing...\n")
        log.debug("Unmerged:\n\t$rawSection Merged:\n\t$stripped")
        return stripped
    }

    public ArrayList getAsDecodedURL(ArrayList parsed)
    {
        for ( a in parsed )
        {
            for (k in a.keySet())
            {
                if(java.lang.String == a[k].getClass())
                    a[k] = decodeURLs(a[k])
            }
        }
        return parsed
    }

    public ArrayList swapInternalAndExternalURL()
    {
        def extras = getExtrasAsArray()
        def decoded = getAsDecodedURL(extras)

        def diff =  decoded[0] - parsedSection[0]
        
        println parsedSection.getClass()
        println parsedSection[0].getClass()
        //Do a deep copy here
        def ret = parsedSection 
        ret[0]['scmUrl'] = diff['internalScmUrl']
        return ret
    }

    public void adjustProject()
    {

    }
    //public Yaml decodeURLs()
    //{
    //    //return java.netURLDecoder.decode(encodedurl, "UTF-8");
    //}

}

/* Pre-parser and amalgimator */
@Slf4j
class BuildConfig {
    private String rawFileContents
    private Map<String, String> mavenProperties
    public Yaml parsedAmalgimatedYaml = new Yaml()
    private def buildConfigs = []

    /* Steps 
    1) Read file from maven
    2) Read properties from maven
    3) Preparse
    4) Preparsed -> BuildConfigSection[]
    5) Amalgimate sections
    */

    
    public BuildConfig(String filePath, Map<String> properties)
    {
        this.BuildConfig(new File(filePath), properties)
    }

    public BuildConfig(java.io.File file, Map<String> properties)
    {
        rawFileContents = file.getText('UTF-8')
        mavenProperties = properties
        this.preParse()
    }

    private void preParse()
    {
        //Skip Ahead in string to "builds:"
        def spl = rawFileContents.split("builds:")

        //Dont try and parse extras
        Pattern extraPattern = Pattern.compile("(outputPrefixes:.*)")
        def stripExtra = extraPattern.split(spl[1])

        //Read each " - name: " section (grab with newline delim)"
        Pattern sectionPattern = Pattern.compile("(?<!#)(- name:)")
        def splPattern = sectionPattern.split(stripExtra[0])
        
        for ( p in splPattern )
        {
            if(p.length() > 2)
            {
                log.debug("Preparsing BC sections")
                log.debug("\n\n\n" + "-------START------\n" + "- name: $p" + "########END#######\n")
                def section = new BuildConfigSection("- name: "+p)
                buildConfigs.add(section)
                println section.swapInternalAndExternalURL()
                println section.getOriginal()
            }
        }
    }
}


//List all properties
//project.getProperties().each { k, v -> println "${k}:${v}" }

//org.apache.maven.project.MavenProject project
def yamlpath = Paths.get(project.getBuild().getDirectory(), "extra-resources", "build.yaml").toString()
log.info("Attempting to load file $yamlpath") 
def rawYamlFileH = new File(yamlpath)
BuildConfig bc = new BuildConfig(rawYamlFileH, project.getProperties())

//println "Test property" + project.properties.project

/*
class Main
{
    static void main(def args)
    {
        //BuildConfig bc = BuildConfig(new File("$project.properties['project.build.outputDirectory']", "build.yaml"))
    }
}*/
