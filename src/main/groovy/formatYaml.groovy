package com.redhat.fuse

import org.yaml.snakeyaml.Yaml
//import org.jboss.prod.preProcessYaml
import java.nio.file.Paths
import java.nio.file.Path
import java.util.regex.Pattern
import java.util.regex.Matcher
import groovy.util.logging.Slf4j
import java.net.URLDecoder
import groovy.json.JsonOutput.*
import com.rits.cloning.Cloner
import java.net.URI

@Slf4j
class versionManipulator 
{
    //public static final Pattern OSGIPattern = Pattern.compile("(?:\\w*)(?<major>\\d+)\\.(?<minor>\\d+)\\.(?<micro>\\d+)(?:.|-)(?:<qualifier>(\\w*|\\d)*.*)")
    public static final Pattern OSGIPattern = Pattern.compile("(?:[\\w|-]*)((?<major>\\d+)\\.(?<minor>\\d+)\\.(?<micro>\\d+)){1}(?:[\\.|-])?(?<qualifier>.*)")
    //public static final Pattern redhatQualifierPattern = Pattern.compile("^[\w-]*-?redhat-(\d+)")

    protected String original

    //Our version deciminated to ints
    public Integer major = 0
    public Integer minor = 0
    public Integer micro = 0
    public String qualifier

    versionManipulator(Integer major, Integer minor, Integer micro, String qualifier)
    {
        log.info("Maj: $major Minor: $minor Micro: $micro Qualifier: $qualifier")
        this.major = major
        this.minor = minor
        this.micro = micro
        this.qualifier = qualifier
    }

    versionManipulator(String v)
    {
        //Grab our likely version string
        log.debug("Looking for version in string $v")
        original = (String) v

        Matcher m = OSGIPattern.matcher(v)

        if (m.find())
        {
            for (int i=0; i < m.groupCount(); i++)
            {
                String mat = m.group(i)
                log.debug("Found $mat")
            }
            major = major.valueOf(m.group("major"))
            minor = minor.valueOf(m.group("minor"))
            micro = micro.valueOf(m.group("micro"))
            qualifier = m.group("qualifier")
        }

    }

    public String getOriginal()
    {
        return original
    }

    @Override
    public String toString()
    {
        return String.format("%s.%s", semverString(), qualifier)
    }

    public String semverString()
    {
        return String.format("%d.%d.%d", major, minor, micro)
    }

    public ArrayList<Integer> semverArray()
    {
        return ArrayList[major, minor, micro]
    }
    
    public Map<String, Integer> semverMap()
    {
        Map<String, Integer> semverMap = new HashMap<String, Integer>(
            "major":major,
            "minor":minor,
            "micro":micro);
    }
   
    public String swap(versionManipulator other)
    {
        def o = this.original
        return o.replaceAll(semverString(), other.semverString())
    }
    /*
    public Integer compare(versionManipulator other)
    {
        //-1 less than
        //0 match 
        //1 greater than
    }
    */
    /*
    versionManipulator(versionManipulator)
    {
    }
    */
}

/* BuildConfigSection yaml */
@Slf4j
class BuildConfigSection {

    private final ArrayList parsedSection
    private final String rawSection
    public ArrayList adjustedParsedSection 
    
    BuildConfigSection(String rawSection)
    {
        //Copy our section
        this.rawSection = rawSection
        def sectionHeader = (rawSection.split("\n")[0]).replace("- name:  ", "")
        
        log.info("Parsing build section: $sectionHeader")
        
        //Parse it
        def yml = new Yaml()
        parsedSection = yml.load(this.rawSection)

        //Make a cloned "working copy"
        def cloner = new Cloner()
        adjustedParsedSection = cloner.deepClone(parsedSection)
    }

    public ArrayList getOriginal()
    {
        return parsedSection
    }

    public ArrayList getAdjusted()
    {
        return adjustedParsedSection
    }

    public String decodeURLs(String encodedURL)
    {
        return URLDecoder.decode(encodedURL, "UTF-8");
    }

    public void commentsToArray()
    {
        adjustedParsedSection = new Yaml().load(commentsToString(rawSection))
    }

    public String commentsToString(String raw)
    {
        def stripped = raw.replaceAll("([\\s]{4}#)", "  ")
        log.debug("Merging commented fields and reparsing...\n")
        log.debug("Unmerged:\n\t$rawSection Merged:\n\t$stripped")
        return stripped
    }

    public void decodedURL()
    {
        for ( a in adjustedParsedSection )
        {
            for (k in a.keySet())
            {
                if(java.lang.String == a[k].getClass())
                    a[k] = decodeURLs(a[k])
            }
        }
    }

    public void swapInternalAndExternalURL()
    {
        this.commentsToArray()
        this.decodedURL()

        def diff =  getAdjusted()[0] - getOriginal()[0]

        if(diff['internalScmUrl'])
        {
            adjustedParsedSection[0]['scmUrl'] = diff['internalScmUrl']
        }
    }

    public void adjustBuildConfigName()
    {
        def version = new versionManipulator(getAdjusted()[0]['name'])
        def scmVersion = new versionManipulator(getAdjusted()[0]['scmRevision'])
        if (!version.semverMap().equals(scmVersion.semverMap()))
        {
            log.info("Adjusting version in BC name as it differs from tag:")
            log.debug("version: " + (String)version.semverMap() + " scmVersion: " + (String)scmVersion.semverMap())
            def orig = version.getOriginal()
            def altered = version.swap(scmVersion)
            adjustedParsedSection[0]['name'] = altered
            log.info("Before: $orig After: $altered")
        }
    }

    public void adjustProjectName()
    {
        def project = getAdjusted()[0]['project']
        def uri = URI(getAdjusted()[0]['scmUrl'])
        String repo, proj = project.split("/")
        //Parse the URI and get path section (we could do this with regex but i'm lazy)
        def path = uri.getPath()
        String srepo, sproj = path.split("/")
        if(proj.equals(sproj))
            print "same project!"
    }

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
                section.swapInternalAndExternalURL()
                section.adjustBuildConfigName()
                section.adjustProjectName()
                //println section.getAdjusted()
                //println section.getOriginal()
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
