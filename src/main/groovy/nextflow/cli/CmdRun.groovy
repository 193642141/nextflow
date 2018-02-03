/*
 * Copyright (c) 2013-2018, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2018, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.cli

import java.nio.file.Files
import java.nio.file.Path

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsConfig
import nextflow.Const
import nextflow.config.ConfigBuilder
import nextflow.exception.AbortOperationException
import nextflow.file.FileHelper
import nextflow.k8s.K8sDriverLauncher
import nextflow.scm.AssetManager
import nextflow.script.ScriptFile
import nextflow.script.ScriptRunner
import nextflow.util.CustomPoolFactory
import nextflow.util.Duration
import nextflow.util.HistoryFile
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

/**
 * CLI sub-command RUN
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
@Command(name = "run", description = "Execute a pipeline project", abbreviateSynopsis = true)
class CmdRun extends CmdBase implements HubOptions {

    static public String NAME = 'run'

    static List<String> VALID_PARAMS_FILE = ['json', 'yml', 'yaml']

    static {
        // install the custom pool factory for GPars threads
        GParsConfig.poolFactory = new CustomPoolFactory()
    }

    static class DurationConverter implements ITypeConverter<Long> {
        @Override
        Long convert(String value) {
            if( !value ) throw new IllegalArgumentException()
            if( value.isLong() ) {  return value.toLong() }
            return Duration.of(value).toMillis()
        }
    }

    @Option(names=['--name'], description = 'Assign a mnemonic name to the a pipeline run',paramLabel = "<String>")
    String runName

    @Option(names=['--lib'], description = 'Library extension path',paramLabel = "Path")
    String libPath

    @Option(names=['--cache'], description = 'Enable/disable processes caching', arity = '0..1',paramLabel = "<Boolean>")
    boolean cacheable = true

    @Option(names=['--resume'], description = 'Execute the script using the cached results, useful to continue executions that was stopped by an error',paramLabel = "<SessionID>",arity = "0..1")
    String resume

    @Option(names=['--ps','--pool-size'], description = 'Number of threads in the execution pool', hidden = true,paramLabel = "<Int>")
    Integer poolSize

    @Option(names=['--pi','--poll-interval'], description = 'Executor poll interval (duration string ending with ms|s|m)', converter = [DurationConverter], hidden = true,paramLabel = "<String>")
    long pollInterval

    @Option(names=['--qs','--queue-size'], description = 'Max number of processes that can be executed in parallel by each executor',paramLabel = "<Int>")
    Integer queueSize

    @Option(names=['--test'], description = 'Test a script function with the name specified',paramLabel = "<String>")
    String test

    @Option(names=['-w', '--work-dir'], description = 'Directory where intermediate result files are stored',arity = '1',paramLabel = "Path")
    String workDir

    /**
     * Defines the parameters to be passed to the pipeline script
     */
    @Option(names = ['--'], description = 'Set a parameter used by the pipeline', hidden = true)
    Map<String,String> params = new LinkedHashMap<>()

    @Option(names=['--params-file'], description = 'Load script parameters from a JSON/YAML file',paramLabel = "<File>")
    String paramsFile

    @Option(names = ['--process.'], description = 'Set process options' ,paramLabel = "<Key:Value>")
    Map<String,String> process = [:]

    @Option(names = ['--e.'], description = 'Add the specified variable to execution environment',paramLabel = "<Key:Value>")
    Map<String,String> env = [:]

    @Option(names = ['-E'], description = 'Exports all current system environment')
    boolean exportSysEnv

    @Option(names = ['--executor'], description = 'Set executor options', hidden = true,paramLabel = "<Key:Value>" )
    Map<String,String> executorOptions = [:]

    @Parameters(description = 'Project name or repository url',paramLabel = "Project_Name") //TODO arity >=1 ?? when we want arity==0?
    List<String> args

    @Option(names=['-r','--revision'], description = 'Revision of the project to run (either a git branch, tag or commit SHA number)',paramLabel = "Revision_Name")
    String revision

    @Option(names=['--latest'], description = 'Pull latest changes before run')
    boolean latest

    @Option(names=['--stdin'], hidden = true)
    boolean stdin

    @Option(names = ['--with-drmaa'], description = 'Enable DRMAA binding')
    String withDrmaa

    @Option(names = ['--with-trace'], description = 'Create processes execution tracing file',paramLabel = "<File>")
    String withTrace

    @Option(names = ['--with-report'], description = 'Create processes execution html report',paramLabel = "<File>")
    String withReport

    @Option(names = ['--with-timeline'], description = 'Create processes execution timeline file',paramLabel = "<File>")
    String withTimeline

    @Option(names = ['--with-singularity'], description = 'Enable process execution in a Singularity container',paramLabel = "Singularity_Container")
    def withSingularity

    @Option(names = ['--with-docker'], description = 'Enable process execution in a Docker container',paramLabel = "Docker_Container")
    def withDocker

    @Option(names = ['--without-docker'], description = 'Disable process execution with Docker', arity = '0')
    boolean withoutDocker

    @Option(names = ['--with-k8s', '-K'], description = 'Enable execution in a Kubernetes cluster',paramLabel = "KubernetesID")
    def withKubernetes

    @Option(names = ['--with-mpi'], hidden = true)
    boolean withMpi

    @Option(names = ['--with-dag'], description = 'Create pipeline DAG file',paramLabel = "<File>")
    String withDag

    @Option(names = ['--bg'], arity = '0', hidden = true)
    boolean backgroundFlag

    @Option(names=['-c','--config'], hidden = true)
    List<String> runConfig

    @Option(names = ['--cluster'], description = 'Set cluster options', hidden = true ,paramLabel = "<Key:Value>")
    Map<String,String> clusterOptions = [:]

    @Option(names=['--profile'], description = 'Choose a configuration profile',paramLabel = "Profile")
    String profile

    @Option(names=['--dump-hashes'], description = 'Dump task hash keys for debugging purpose')
    boolean dumpHashes

    @Option(names=['--dump-channels'], description = 'Dump channels for debugging purpose',paramLabel = "ChannelsName")
    String dumpChannels

    @Option(names=['-N','--with-notification'], description = 'Send a notification email on workflow completion to the specified recipients',paramLabel = "********")
    String withNotification

    @Override
    void run() {
        launcher.options.background = backgroundFlag
        final scriptArgs = (args?.size()>1 ? args[1..-1] : []) as List<String>
        final pipeline = stdin ? '-' : ( args ? args[0] : null )
        if( !pipeline )
            throw new AbortOperationException("No project name was specified")

        if( withDocker && withoutDocker )
            throw new AbortOperationException("Command line options `-with-docker` and `-without-docker` cannot be specified at the same time")

        checkRunName()

        if( withKubernetes ) {
            // that's another story
            new K8sDriverLauncher(cmd: this, runName: runName).run(pipeline, scriptArgs)
            return
        }

        log.info "N E X T F L O W  ~  version ${Const.APP_VER}"

        // -- specify the arguments
        final scriptFile = getScriptFile(pipeline)

        // create the config object
        final config = new ConfigBuilder()
                        .setOptions(launcher.options)
                        .setCmdRun(this)
                        .setBaseDir(scriptFile.parent)

        // -- create a new runner instance
        final runner = new ScriptRunner(config)
        runner.script = scriptFile
        runner.profile = profile

        if( this.test ) {
            runner.test(this.test, scriptArgs)
            return
        }

        def info = CmdInfo.status( log.isTraceEnabled() )
        log.debug( '\n'+info )

        // -- add this run to the local history
        runner.verifyAndTrackHistory(launcher.cliString, runName)

        // -- run it!
        runner.execute(scriptArgs)
    }

    private void checkRunName() {
        if( runName == 'last' )
            throw new AbortOperationException("Not a valid run name: `last`")

        if( !runName ) {
            // -- make sure the generated name does not exist already
            runName = HistoryFile.DEFAULT.generateNextName()
        }

        else if( HistoryFile.DEFAULT.checkExistsByName(runName) )
            throw new AbortOperationException("Run name `$runName` has been already used -- Specify a different one")
    }

    protected ScriptFile getScriptFile(String pipelineName) {
        assert pipelineName

        /*
         * read from the stdin
         */
        if( pipelineName == '-' ) {
            def file = tryReadFromStdin()
            if( !file )
                throw new AbortOperationException("Cannot access `stdin` stream")

            if( revision )
                throw new AbortOperationException("Revision option cannot be used running a local script")

            return new ScriptFile(file)
        }

        /*
         * look for a file with the specified pipeline name
         */
        def script = new File(pipelineName)
        if( script.isDirectory()  ) {
            script = new AssetManager().setLocalPath(script).getMainScriptFile()
        }

        if( script.exists() ) {
            if( revision )
                throw new AbortOperationException("Revision option cannot be used running a script")
            def result = new ScriptFile(script)
            log.info "Launching `$script` [$runName] - revision: ${result.getScriptId()?.substring(0,10)}"
            return result
        }

        /*
         * try to look for a pipeline in the repository
         */
        def manager = new AssetManager(pipelineName, this)
        def repo = manager.getProject()

        boolean checkForUpdate = true
        if( !manager.isRunnable() || latest ) {
            log.info "Pulling $repo ..."
            def result = manager.download()
            if( result )
                log.info " $result"
            checkForUpdate = false
        }
        // checkout requested revision
        try {
            manager.checkout(revision)
            manager.updateModules()
            def scriptFile = manager.getScriptFile()
            log.info "Launching `$repo` [$runName] - revision: ${scriptFile.revisionInfo}"
            if( checkForUpdate )
                manager.checkRemoteStatus(scriptFile.revisionInfo)
            // return the script file
            return scriptFile
        }
        catch( AbortOperationException e ) {
            throw e
        }
        catch( Exception e ) {
            throw new AbortOperationException("Unknown error accessing project `$repo` -- Repository may be corrupted: ${manager.localPath}", e)
        }

    }

    static protected File tryReadFromStdin() {
        if( !System.in.available() )
            return null

        getScriptFromStream(System.in)
    }

    static protected File getScriptFromStream( InputStream input, String name = 'nextflow' ) {
        input != null
        File result = File.createTempFile(name, null)
        result.deleteOnExit()
        input.withReader { Reader reader -> result << reader }
        return result
    }

    Map getParsedParams() {

        def result = [:]

        if( paramsFile ) {
            def path = validateParamsFile(paramsFile)
            def ext = path.extension.toLowerCase() ?: null
            if( ext == 'json' )
                readJsonFile(path, result)
            else if( ext == 'yml' || ext == 'yaml' )
                readYamlFile(path, result)
        }

        // read the params file if any

        // set the CLI params
        params?.each { key, value ->
            result.put( key, parseParam(value) )
        }
        return result
    }

    static private parseParam( String str ) {

        if ( str == null ) return null

        if ( str.toLowerCase() == 'true') return Boolean.TRUE
        if ( str.toLowerCase() == 'false' ) return Boolean.FALSE

        if ( str.isInteger() ) return str.toInteger()
        if ( str.isLong() ) return str.toLong()
        if ( str.isDouble() ) return str.toDouble()

        return str
    }

    private Path validateParamsFile(String file) {

        def result = FileHelper.asPath(file)
        if( !result.exists() )
            throw new AbortOperationException("Specified params file does not exists: $file")

        def ext = result.getExtension()
        if( !VALID_PARAMS_FILE.contains(ext) )
            throw new AbortOperationException("Not a valid params file extension: $file -- It must be one of the following: ${VALID_PARAMS_FILE.join(',')}")

        return result
    }


    private void readJsonFile(Path file, Map result) {
        try {
            def json = (Map)new JsonSlurper().parse(Files.newInputStream(file))
            result.putAll(json)
        }
        catch( Exception e ) {
            throw new AbortOperationException("Cannot parse params file: $file", e)
        }
    }

    private void readYamlFile(Path file, Map result) {
        try {
            def yaml = (Map)new Yaml().load(Files.newInputStream(file))
            result.putAll(yaml)
        }
        catch( Exception e ) {
            throw new AbortOperationException("Cannot parse params file: $file", e)
        }
    }

}
