/* groovylint-disable LineLength, UnnecessaryGetter */

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.artifactory.exception.CancelException
import org.artifactory.fs.ItemInfo
import org.artifactory.fs.StatsInfo
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.resource.ResourceStreamHandle

import groovy.transform.Field

@Field final String CONFIG_FILE_PATH = "plugins/${this.class.name}.json"
@Field final Long DEFAULT_TIME_INTERVAL = 30

/**
 * Рекурсивно сканирует содержимое артефакта в репозитории. Если артефакт является папкой,
 * функция рекурсивно вызывается для каждого дочернего элемента. Если артефакт является файлом,
 * применяется валидатор для проверки его состояния. Если валидация проходит успешно,
 * выполняется действие, определенное в режиме исполнения.
 *
 * @param artifact объект ItemInfo, представляющий артефакт для сканирования.
 * @param executionMode объект ExecutionMode, определяющий действие, которое следует выполнить с артефактом,
 *                      в случае прохождения валидации.
 * @param validator объект Validator, используемый для валидации артефакта.
 * */
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

/**
 * Обходит все локальные репозитории, исключая те, что указаны в списке исключений.
 * Для каждого артефакта в репозитории вызывается функция сканирования содержимого с переданными
 * режимом исполнения и валидатором.
 *
 * @param executionMode объект ExecutionMode, определяющий действие, которое следует выполнить с артефактом,
 *                      в случае прохождения валидации.
 * @param validator объект Validator, используемый для валидации артефакта.
 * @param exclude список имен репозиториев, которые следует исключить из обхода.
 * */
void rotateRegularFunc(ExecutionMode executionMode, Validator validator, List<String> exclude) {
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
            description: 'description',
            httpMethod: 'POST',
            users: [],
            groups: [],
            params: [:]) { params, ResourceStreamHandle body ->
        log.debug('Start')
        assert body
        def json = new JsonSlurper().parse(new InputStreamReader(body.inputStream))
        log.debug("$json.dryRun")
        log.debug("$json.interval")
        log.debug("$json.exclude")

        executionMode = createExecutionMode(json.dryRun, repositories, log)
        validator = new LastModifiedDayIntervalValidator(json.interval, log, repositories)

        rotateRegularFunc(executionMode, validator, json.exclude)
    }
}

def configFile = new File(ctx.artifactoryHome.etcDir, CONFIG_FILE_PATH)

if (configFile.exists()) {
    def config = new JsonSlurper().parse(configFile.toURL())
    log.info "Schedule job policy list: $config.policies"

    def count = 1
    config.policies.each { policySettings ->
        def cron = policySettings.containsKey('cron') ? policySettings.cron as String : ["0 0 5 ? * 1"]
        def interval = policySettings.containsKey('interval') ? policySettings.interval as Long : DEFAULT_TIME_INTERVAL
        def dryRun = policySettings.containsKey('dryRun') ? new Boolean(policySettings.dryRun) : true
        def exclude = policySettings.containsKey('exclude') ? policySettings.exclude as List<String> : ['Library', 'build']

        jobs {
            /**
            * A job definition.
            * The first value is a unique name for the job.
            * Job runs are controlled by the provided interval or cron expression, which are mutually exclusive.
            * The actual code to run as part of the job should be part of the job's closure.
            *
            * Parameters:
            * cron (java.lang.String) - A valid cron expression used to schedule job runs
            **/
            "scheduledCleanup_$count"(cron: cron) {
                log.info "Policy settings for scheduled run at($cron): Skiped repos list($exclude), timeInterval($interval), exec ($dryRun)"
                executionMode = createExecutionMode(dryRun, repositories, log)
                validator = new LastModifiedDayIntervalValidator(interval, log, repositories)

                rotateRegularFunc(executionMode, validator, exclude)
            }
        }
        count++
    }
}

abstract class ArtifactoryContext {
    def log
    def repositories
}

/**
 * Интерфейс, отвечающий за определение того, подлежит ли артефакт удалению
 */
interface Validator {
    /**
     * Метод валидации. Принимает RepoPath в качестве параметра и возвращает булевый результат
     *
     * @param artefactPath путь к репозиторию артефакта для валидации
     * @return true, если артефакт подлежит удалению, иначе false
     */
    Boolean validate(RepoPath artefactPath)

}

/**
 * Интерфейс, отвечающий за выполнение удаления артефакта
 */
interface ExecutionMode {
    /**
     * Метод выполнения. Принимает RepoPath в качестве параметра и выполняет удаление артефакта
     *
     * @param repoPath путь к репозиторию артефакта для удаления
     */
    void execute(RepoPath repoPath)

}

/**
 * Класс, отвечающий за валидацию артефактов на основе даты их последнего изменения
 */
class LastModifiedDayIntervalValidator extends ArtifactoryContext implements Validator {

    Long interval
    /**
     * Конструктор класса.
     *
     * @param interval интервал в днях, который используется для валидации даты последнего изменения артефакта.
     * @param log объект для логирования действий валидатора.
     * @param repositories объект, предоставляющий доступ к репозиториям.
     * */
    LastModifiedDayIntervalValidator(Long interval, log, repositories) {
        this.interval = interval
        this.log = log
        this.repositories = repositories
    }

    Boolean validate(RepoPath artefactPath) {
        Long rotationIntervalMillis = interval as Long * 24 * 60 * 60 * 1000
        long cutoff = new Date().time - rotationIntervalMillis
        ItemInfo item = repositories.getItemInfo(artefactPath)
        return item.getLastModified() < cutoff
    }

}

/**
 * Класс, отвечающий за валидацию артефактов на основе даты их последней загрузки
 */
class LastDownloadedIntervalValidator extends ArtifactoryContext implements Validator {

    Long interval

    /**
     * Конструктор класса.
     *
     * @param interval интервал в днях, который используется для валидации даты скачки артефакта.
     * @param log объект для логирования действий валидатора.
     * @param repositories объект, предоставляющий доступ к репозиториям.
     */
    LastDownloadedIntervalValidator(Long interval, log, repositories) {
        this.interval = interval
        this.log = log
        this.repositories = repositories
    }

    Boolean validate(RepoPath artefactPath) {
        long rotationIntervalMillis = interval * 24 * 60 * 60 * 1000
        long cutoff = new Date().time - rotationIntervalMillis
        StatsInfo stats = repositories.getStats(artefactPath)
        return stats != null ? stats.getLastDownloaded() < cutoff : true
    }

}

/**
 * Класс, отвечающий за режим "сухого прогона" удаления
 */
class DryExecutionMode extends ArtifactoryContext implements ExecutionMode {
    /**
     * Создаёт новый объект DryExecutionMode.
     *
     * @param repositories объект, предоставляющий доступ к репозиториям.
     * @param log объект для логирования действий режима исполнения.
     * */
    DryExecutionMode(repositories, log) {
        this.repositories = repositories
        this.log = log
    }

    void execute(RepoPath repoPath) {
        log.info("Артефакт был бы удален: {}", repoPath.toPath())
    }

}

/**
 * Класс, отвечающий за режим фактического удаления
 */
class DeleteExecutionMode extends ArtifactoryContext implements ExecutionMode {
    /**
     * Создаёт новый объект DeleteExecutionMode.
     *
     * @param repositories объект, предоставляющий доступ к репозиториям.
     * @param log объект для логирования действий режима исполнения.
     * */
    DeleteExecutionMode(repositories, log) {
        this.repositories = repositories
        this.log = log
    }

    void execute(RepoPath repoPath) {
        repositories.delete(repoPath)
        log.info("Артефакт был удален: {}", repoPath.toPath())
    }

}

static ExecutionMode createExecutionMode(Boolean isDryMode, def repositories, def log) {
    return isDryMode ? new DryExecutionMode(repositories, log) : new DeleteExecutionMode(repositories, log)
}
