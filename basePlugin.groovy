/* groovylint-disable LineLength, UnnecessaryGetter */
import groovy.json.JsonSlurper
import org.artifactory.exception.CancelException
import org.artifactory.fs.ItemInfo
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.resource.ResourceStreamHandle

/**
 * Сканирует репозиторий и его подпапки рекурсивно.
 * Удаляет артефакты, которые были изменены более указанного интервала назад.
 *
 * @param dryRun если true, то фактическое удаление не производится, только ведется логирование
 * @param artifact артефакт для сканирования
 * @param interval интервал в миллисекундах, артефакты старше этого интервала подлежат удалению
 */
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

/**
 * Функция ротации обычных репозиториев. Итерирует локальные репозитории,
 * игнорирует репозитории, перечисленные в списке 'exclude'.
 *
 * @param dryRun если true, то фактическое удаление не производится, только ведется логирование
 * @param regularInterval интервал в днях, артефакты старше этого интервала подлежат удалению
 * @param exclude список ключей репозиториев, которые исключаются из вращения
 */
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

        executionMode = createExecutionMode(json.dryRun, ctx, log)
        validator = new LastModifiedDayIntervalValidator(json.interval, log, repositories)

        rotateRegular(executionMode, validator, json.exclude)
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
     * @param repoPath путь к репозиторию артефакта для валидации
     * @return true, если артефакт подлежит удалению, иначе false
     */
    Boolean validate(RepoPath repoPath)

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
     * Создаёт новый объект LastModifiedDayIntervalValidator.
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

    Boolean validate(RepoPath repoPath) {
        long rotationIntervalMillis = this.interval * 24 * 60 * 60 * 1000
        long cutoff = new Date().time - rotationIntervalMillis
        ItemInfo item = repositories.getItemInfo(repoPath)
        return item.getLastModified() < cutoff
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
