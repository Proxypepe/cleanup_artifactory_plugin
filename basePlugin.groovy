/* groovylint-disable LineLength, UnnecessaryGetter */
import groovy.json.JsonSlurper
import org.artifactory.fs.ItemInfo
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.resource.ResourceStreamHandle
import org.artifactory.repo.Repositories

/**
 * Scans folders recursively.
 *
 * @param dryRun -
 * @param folderItem -
 * @param interval -
 **/
void scanRepositoryContentArtifact(Boolean dryRun, ItemInfo artifact, long interval) {
    RepoPath fullArtifactRepo = RepoPathFactory.create(artifact.getRepoKey(), artifact.getRelPath())
    if (artifact.isFolder()) {
        repositories.getChildren(fullArtifactRepo).each { item ->
            scanRepositoryContentArtifact(dryRun, item, interval)
        }
    } else {
        // repositories.getStats(fullArtifactRepo)
        if (artifact.getLastModified() < interval) {
            if (dryRun) {
                log.info('Файл был бы удалё: {}', artifact.getName())
            } else {
                repositories.delete(fullArtifactRepo)
                log.info('File: {}', artifact.getName())
            }
        }
    }
}

void rotateRegular(Boolean dryRun, int regularInterval, List<String> exclude) {
    log.debug("In func $dryRun")
    log.debug("In func $regularInterval")
    log.debug("In func $exclude")
    log.debug('Repos {}', repositories.getLocalRepositories())
    long rotationIntervalMillis = regularInterval * 24 * 60 * 60 * 1000
    long cutoff = new Date().time - rotationIntervalMillis
    repositories.getLocalRepositories().each { repoKey ->
        if (exclude.contains(repoKey)) {
            log.info("Пропускаем репозиторий: $repoKey")
            return
        }

        log.info("Обрабатываем репозиторий: $repoKey")

        repositories.getChildren(RepoPathFactory.create(repoKey)).each { item ->
            scanRepositoryContentArtifact(dryRun, item, cutoff)
        }
    }
}

void scanRepositoryContentArtifact(ItemInfo artifact, ExecutionMode executionMode, Validator validator) {
    RepoPath fullArtifactRepo = RepoPathFactory.create(artifact.getRepoKey(), artifact.getRelPath())
    if (artifact.isFolder()) {
        repositories.getChildren(fullArtifactRepo).each { item ->
            scanRepositoryContentArtifact(item, executionMode, validator)
        }
    } else {
        if (validator.validate(fullArtifactRepo)) {
            executionMode.execute(fullArtifactRepo)
        }
    }
}

void rotateRegular(ExecutionMode executionMode, Validator validator, List<String> exclude) {
    repositories.getLocalRepositories().each { repoKey ->
        if (exclude.contains(repoKey)) {
            log.info("Пропускаем репозиторий: $repoKey")
            return
        }
        log.info("Обрабатываем репозиторий: $repoKey")
        repositories.getChildren(RepoPathFactory.create(repoKey)).each { item ->
            scanRepositoryContentArtifact(item, executionMode, validator)
        }
    }
}

executions {
    /**
     * An execution definition.
     * The first value is a unique name for the execution.
     *
     * Context variables:
     * status (int) - a response status code. Defaults to -1 (unset). Not applicable for an async execution.
     * message (java.lang.String) - a text message to return in the response body, replacing the response content.
     *                              Defaults to null. Not applicable for an async execution.
     *
     * Plugin info annotation parameters:
     *  version (java.lang.String) - Closure version. Optional.
     *  description (java.lang.String) - Closure description. Optional.
     *  httpMethod (java.lang.String, values are GET|PUT|DELETE|POST) - HTTP method this closure is going
     *    to be invoked with. Optional (defaults to POST).
     *  params (java.util.Map<java.lang.String, java.lang.String>) - Closure default parameters. Optional.
     *  users (java.util.Set<java.lang.String>) - Users permitted to query this plugin for information or invoke it.
     *  groups (java.util.Set<java.lang.String>) - Groups permitted to query this plugin for information or invoke it.
     *
     * Closure parameters:
     *  params (java.util.Map) - An execution takes a read-only key-value map that corresponds to the REST request
     *    parameter 'params'. Each entry in the map contains an array of values. This is the default closure parameter,
     *    and so if not named it will be "it" in groovy.
     *  ResourceStreamHandle body - Enables you to access the full input stream of the request body.
     *    This will be considered only if the type ResourceStreamHandle is declared in the closure.
     */

    rotateRegular(
            version: '1',
            description:'description',
            httpMethod: 'POST',
            users:[],
            groups:[],
            params:[:]) { params, ResourceStreamHandle body ->
        log.debug('Start')
        assert body

        def json = new JsonSlurper().parse(new InputStreamReader(body.inputStream))
        log.debug("$json.dryRun")
        log.debug("$json.interval")
        log.debug("$json.exclude")
        executionMode = createExecutionMode(json.dryRun, ctx, log)
        def validator = new LastModifiedDayIntervalValidator(json.interval, log, repositories)
        log.info('Repos is: {}', repositories.getClass())
        // rotateRegular(json.dryRun, json.interval, json.exclude)
        rotateRegular(executionMode, validator, json.exclude)
    }
}
    
abstract class ArtifactoryContext {
    def log
    def repositories
}

public interface Validator {

    Boolean validate(RepoPath repoPath)

}

public interface ExecutionMode {

    void execute(RepoPath repoPath)

}

class LastModifiedDayIntervalValidator extends ArtifactoryContext implements Validator {

    Long interval

    LastModifiedDayIntervalValidator(Long interval, log, repositories) {
        this.interval = interval
        this.log = log
        this.repositories = repositories
    }

    Boolean validate(RepoPath repoPath) {
        long rotationIntervalMillis = this.interval * 24 * 60 * 60 * 1000
        long cutoff = new Date().time - rotationIntervalMillis
        ItemInfo item = repositories.getItemInfo(repoPath)
        return item.getLastModified() < cutoff
    }

}

class DryExecutionMode extends ArtifactoryContext implements ExecutionMode {

    DryExecutionMode(repositories, log) {
        this.repositories = repositories
        this.log = log
    }

    void execute(RepoPath repoPath) {
        log.info("Артефакт был бы удален: {}", repoPath.toPath())
    }

}

class DeleteExecutionMode extends ArtifactoryContext implements ExecutionMode {

    DeleteExecutionMode(repositories, log) {
        this.repositories = repositories
        this.log = log
    }

    void execute(RepoPath repoPath) {
        repositories.delete(repoPath)
        log.info("Артефакт был удален: {}", repoPath.toPath())
    }

}

public static ExecutionMode createExecutionMode(Boolean isDryMode, def repositories, def log) {
    return isDryMode ? new DryExecutionMode(repositories, log) : new DeleteExecutionMode(repositories, log)
}
