import groovy.json.JsonSlurper
import org.artifactory.exception.CancelException
import org.artifactory.fs.ItemInfo
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.resource.ResourceStreamHandle
import org.artifactory.repo.Repositories

// Make recurtion function
void scanFolder(List<ItemInfo> folder, int regularInterval) {
    return
}

void rotateRegular(def dryRun, def regularInterval, def exclude) {

    log.debug("In func $dryRun")
    log.debug("In func $regularInterval")   
    log.debug("In func $exclude")
    log.debug("Repos {}", repositories.getLocalRepositories())
  repositories.getLocalRepositories().each { repoKey ->
    if (exclude.contains(repoKey)) {
      log.info("Пропускаем репозиторий: {}", repoKey)
      return
    }   

    def interval = regularInterval
    def cutoff = new Date() - interval

    log.info("Обрабатываем репозиторий: {}", repoKey)

    repositories.getChildren(RepoPathFactory.create(repoKey)).each { item ->
      log.info("Item {}", item.repoPath )
      if (item.isFolder()){
            log.info("getRepoPath: {}", item.getRepoPath().getPath())
            log.info("getRepoKey: {}", item.getRepoKey())
            log.info("getRelPath: {}", item.getRelPath())
            log.info("Direct {}", repositories.getChildren(RepoPathFactory.create(item.getRepoKey(), item.getRelPath())))
      }
    //   if (!item.isFolder() && item.lastModified < cut    off) {
    //     if (dryRun) {
    //       log.info("Был бы удален: {}", item.repoPath)
    //     } else {
    //       storage.delete(item.repoPath)
    //       log.info("Удален: {}", item.repoPath)
    //     }
    //   }
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

  rotateRegular(version: "1",description:"description", httpMethod: 'POST', users:[], groups:[], params:[:]) { params, ResourceStreamHandle body ->
    log.debug("Start")
    assert body
    def json = new JsonSlurper().parse(new InputStreamReader(body.inputStream))
    log.debug("$json.dryRun")
    log.debug("$json.interval")
    log.debug("$json.exclude")
    rotateRegular(json.dryRun ?: true, json.interval, json.exclude)
  }
}
