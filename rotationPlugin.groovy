/* groovylint-disable LineLength, UnnecessaryGetter */

import groovy.json.JsonSlurper
import org.artifactory.exception.CancelException
import org.artifactory.fs.ItemInfo
import org.artifactory.fs.FileInfo
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
 * @param artefact объект ItemInfo, представляющий артефакт для сканирования.
 * @param executionMode объект ExecutionMode, определяющий действие, которое следует выполнить с артефактом,
 *                      в случае прохождения валидации.
 * @param validator объект Validator, используемый для валидации артефакта.
 * */
void scanRepositoryContentArtefact(ItemInfo artefact, ExecutionMode executionMode, Validator validator) {
    RepoPath fullArtefactRepo = RepoPathFactory.create(artefact.getRepoKey(), artefact.getRelPath())
    if (artefact.isFolder()) {
        repositories.getChildren(fullArtefactRepo).each { item ->
            scanRepositoryContentArtefact(item, executionMode, validator)
        }
        if (repositories.getChildren(fullArtefactRepo).empty) {
            executionMode.execute(fullArtefactRepo)
        }
    } else {
        if (validator.validate(fullArtefactRepo)) {
            executionMode.printArtefactStatistic(fullArtefactRepo)
            executionMode.addArtefactSize(fullArtefactRepo)
            executionMode.execute(fullArtefactRepo)
        }
    }
}

void scanDockerRepository(ItemInfo artefact, RepoPath parentPath, Map<RepoPath, Boolean> map, ExecutionMode executionMode, Validator validator) {
    RepoPath fullArtefactRepo = RepoPathFactory.create(artefact.getRepoKey(), artefact.getRelPath())
    if (artefact.isFolder()) {
        repositories.getChildren(fullArtefactRepo).each { item ->
            scanDockerRepository(item, fullArtefactRepo, map, executionMode, validator)
        }
    } else {
        if (map[parentPath] == null) {
            map[parentPath] = true
        }
        map[parentPath] &= validator.validate(fullArtefactRepo)
    }
}

void rotateRegularDocker(ExecutionMode executionMode, Validator validator, SkipObject excludeObjects) {
    Map<RepoPath, Boolean> map = [:]
    try {
        excludeObjects.repos.each { repoKey ->
            log.info("Processing the repository: $repoKey")
            repositories.getChildren(RepoPathFactory.create(repoKey)).each { item ->
                scanDockerRepository(item, RepoPathFactory.create(repoKey), map, executionMode, validator)
            }
        }
    } catch (Exception ex) {
        log.info('Error message {}', ex.getMessage())
        log.info('Error stack trace {}', ex.getStackTrace())
    }
    log.info(map.toString())

    map.each { it ->
        if (it.value && !it.key.toString().contains(".jfrog"))
            executionMode.execute(it.key)
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
void rotateRegularExclude(ExecutionMode executionMode, Validator validator, SkipObject excludeObjects) {
    try {
        repositories.getLocalRepositories().each { repoKey ->
            if (excludeObjects.repos.contains(repoKey)) {
                log.info("Skipped repositories: $repoKey")
                return
            }

            log.info("Processing the repository: $repoKey")
            repositories.getChildren(RepoPathFactory.create(repoKey)).each { item ->
                if (
                        item.isFolder()
                                && excludeObjects.directories.containsKey(repoKey)
                                && excludeObjects.directories[repoKey]?.contains(item.getName())
                ) {
                    log.info("Skipped directory {}", item.getName())
                    return
                }
                scanRepositoryContentArtefact(item, executionMode, validator)
            }
        }
    } catch (Exception ex) {
        log.info('Error message {}', ex.getMessage())
        log.info('Error stack trace {}', ex.getStackTrace())
    }
}

/**
 * Обходит репозитории, которые были переданы параметром.
 * Для каждого артефакта в репозитории вызывается функция сканирования содержимого с переданными
 * режимом исполнения и валидатором.
 *
 * @param executionMode объект ExecutionMode, определяющий действие, которое следует выполнить с артефактом,
 *                      в случае прохождения валидации.
 * @param validator объект Validator, используемый для валидации артефакта.
 * @param exclude список имен репозиториев, которые следует исключить из обхода.
 * */
void rotateRegularInclude(ExecutionMode executionMode, Validator validator, List<String> include) {
    try {
        include.each { repoKey ->
            log.info("Processing the repository: $repoKey")
            repositories.getChildren(RepoPathFactory.create(repoKey)).each { item ->
                scanRepositoryContentArtefact(item, executionMode, validator)
            }
        }
    } catch (Exception ex) {
        log.info('Error message {}', ex.getMessage())
        log.info('Error stack trace {}', ex.getStackTrace())
    }
}

def configFile = new File(ctx.artifactoryHome.etcDir, CONFIG_FILE_PATH)

private void validateConfigObject(Object json) {
    String message = ''
    if (!(json.cron instanceof String)) {
        message = "cron must be a string"
        log.error(message)
        throw new IllegalArgumentException(message)
    }

    if (!(json.mode in ['exclude', 'include', 'docker'])) {
        message = "mode can only be 'exclude' or 'include'"
        log.error(message)
        throw new IllegalArgumentException(message)
    }
    // Список доступных валидаторов
    if (!(json.validator in ['LastModified', 'LastDownloaded', 'lastmodified', 'lastdownloaded'])) {
        message = "validator can only be 'LastModified' or 'LastDownloaded'"
        log.error(message)
        throw new IllegalArgumentException(message)
    }

    if (!(json.interval instanceof Integer)) {
        message = "interval must be an integer"
        log.error(message)
        throw new IllegalArgumentException(message)
    }

    if (!(json.intervalType in ['inner', 'outer', 'all', 'none'])) {
        message = "Comparator can only be 'inner', 'outer', 'all', 'none'"
        log.error(message)
        throw new IllegalArgumentException(message)
    }

    if (!(json.repos instanceof List)) {
        message = "repos must be an List"
        log.error(message)
        throw new IllegalArgumentException(message)
    }

    if (!(json.dryRun instanceof Boolean)) {
        message = "dryRun must be a boolean value"
        log.error(message)
        throw new IllegalArgumentException(message)
    }

    log.info("Configuration object passed validation")
}

if (configFile.exists()) {
    def config = new JsonSlurper().parse(configFile.toURL())
    log.info "Schedule job policy list: $config.policies"

    def count = 1
    config.policies.each { policySettings ->
        validateConfigObject(policySettings)
        String cron = policySettings.containsKey('cron') ? policySettings.cron as String : "0 0 5 ? * 1"
        String mode = policySettings.containsKey('mode') ? policySettings.mode as String : "include"
        String validatorType = policySettings.containsKey('validator') ? policySettings.validator as String : "LastModified"
        Long interval = policySettings.containsKey('interval') ? policySettings.interval as Long : DEFAULT_TIME_INTERVAL
        String intervalType = policySettings.containsKey('intervalType') ? policySettings.intervalType as String : "outer"
        Boolean dryRun = policySettings.containsKey('dryRun') ? new Boolean(policySettings.dryRun) : true
        List<String> repos = policySettings.containsKey('repos') ? policySettings.repos as List<String> : []
        Map<String, List<String>> directories = policySettings.containsKey('directories') ? policySettings.directories as Map : [:]
        SkipObject objects = new SkipObject(repos, directories)

        jobs {
            "scheduledCleanup_$count"(cron: cron) {
                log.info("objects {}", directories)
                log.info "Policy settings for scheduled run at($cron): repos list($repos), timeInterval($interval), exec ($dryRun), mode($mode)"
                def executionMode = createExecutionMode(dryRun, repositories, log)
                def comparator = createComparator(intervalType, log)
                def validator = createValidator(validatorType, interval, comparator, repositories, log)
                switch (mode) {
                    case 'include':
                        rotateRegularInclude(executionMode, validator, repos)
                        break
                    case 'exclude':
                        rotateRegularExclude(executionMode, validator, objects)
                        break
                    case 'docker':
                        rotateRegularDocker(executionMode, validator, objects)
                        break
                }
                executionMode.printArtefactSize()
            }
        }
        count++
    }
} else {
    log.info("You need to add a config file named ${this.class.name}.json")
}

/**
 * Абстрактный класс ArtefactoryContext, представляющий контекст выполнения в Artefactory.
 * Содержит ссылки на системные объекты, необходимые для выполнения операций с репозиториями.
 *
 * @param log объект для логирования действий и ошибок.
 * @param repositories объект для взаимодействия с репозиториями в Artefactory.
 */
abstract class ArtefactoryContext {
    def log
    def repositories
    long artefactsSize = 0

    /**
     * Добавляет размер артефакта к общему размеру артефактов, подготовленных к удалению.
     *
     * @param artefactPath Путь к артефакту в репозитории.
     */
    void addArtefactSize(RepoPath artefactPath) {
        FileInfo fileInfo = repositories.getFileInfo(artefactPath)
        artefactsSize += fileInfo.getSize()
    }
}

/**
 * Абстрактный класс, отвечающий за определение того, подлежит ли артефакт удалению
 */
abstract class Validator extends ArtefactoryContext {
    Comparator comparator
    Long interval
    /**
     * Метод валидации. Принимает RepoPath в качестве параметра и возвращает булевый результат
     *
     * @param artefactPath путь к репозиторию артефакта для валидации
     * @return true, если артефакт подлежит удалению, иначе false
     */
    abstract Boolean validate(RepoPath artefactPath)

}

/**
 * Интерфейс, отвечающий за выполнение удаления артефакта
 */
abstract class ExecutionMode extends ArtefactoryContext {
    /**
     * Метод выполнения. Принимает RepoPath в качестве параметра и выполняет удаление артефакта
     *
     * @param repoPath путь к репозиторию артефакта для удаления
     */
    abstract void execute(RepoPath repoPath)


    void printArtefactStatistic(RepoPath artefactPath) {
        StatsInfo stats = repositories.getStats(artefactPath)
        FileInfo fileInfo = repositories.getFileInfo(artefactPath)
        String lastDownloaded = null

        if (stats?.getLastDownloaded())
            lastDownloaded = new Date(stats.getLastDownloaded()).format("yyyy-MM-dd HH:mm")

        log.info("###FilePath@{};FileName@{};RepoKey@{};FileSize@{};stats@{};Created@{};LastModified@{}",
                artefactPath,
                fileInfo.getName(),
                fileInfo.getRepoKey(),
                fileInfo.getSize(),
                lastDownloaded,
                new Date(fileInfo.getCreated()).format("yyyy-MM-dd HH:mm"),
                new Date(fileInfo.getLastModified()).format("yyyy-MM-dd HH:mm")
        )
    }

    /**
     * Выводит размер артефактов в удобочитаемом формате (байты, мегабайты или гигабайты).
     */
    void printArtefactSize() {
        long divider = 1024 * 1024
        String type = "MB"
        double tmp = artefactsSize / divider
        if (tmp > 1024.0) {
            divider *= 1024
            type = "GB"
        }
        // log.info("Final size of artefacts {:.2f} in Bytes, {:.2f} in {}", artefactsSize, (artefactsSize / divider), type)
        log.info("Final size of artefacts {} in Bytes, {} in {}", artefactsSize, (artefactsSize / divider), type)
    }
}

/**
 * Класс, отвечающий за валидацию артефактов на основе даты их последнего изменения
 */
class LastModifiedDayIntervalValidator extends Validator {

    /**
     * Конструктор класса.
     *
     * @param interval интервал в днях, который используется для валидации даты последнего изменения артефакта.
     * @param log объект для логирования действий валидатора.
     * @param repositories объект, предоставляющий доступ к репозиториям.
     * */
    LastModifiedDayIntervalValidator(Long interval, Comparator comparator, log, repositories) {
        this.interval = interval
        this.log = log
        this.repositories = repositories
        this.comparator = comparator
    }

    Boolean validate(RepoPath artefactPath) {
        Long rotationIntervalMillis = interval as Long * 24 * 60 * 60 * 1000
        long cutoff = new Date().time - rotationIntervalMillis
        ItemInfo item = repositories.getItemInfo(artefactPath)
        return comparator.compare(item.getLastModified(), cutoff)
    }

}

/**
 * Класс, отвечающий за валидацию артефактов на основе даты их последней загрузки
 */
class LastDownloadedIntervalValidator extends Validator {

    /**
     * Конструктор класса.
     *
     * @param interval интервал в днях, который используется для валидации даты скачки артефакта.
     * @param log объект для логирования действий валидатора.
     * @param repositories объект, предоставляющий доступ к репозиториям.
     */
    LastDownloadedIntervalValidator(Long interval, Comparator comparator, log, repositories) {
        this.interval = interval
        this.log = log
        this.repositories = repositories
        this.comparator = comparator
    }

    Boolean validate(RepoPath artefactPath) {
        long rotationIntervalMillis = this.interval * 24 * 60 * 60 * 1000
        long cutoff = new Date().time - rotationIntervalMillis
        StatsInfo stats = repositories.getStats(artefactPath)
        return stats != null ? comparator.compare(stats.getLastDownloaded(), cutoff) : true
    }

}

/**
 * Класс, отвечающий за режим "сухого прогона" удаления
 */
final class DryExecutionMode extends ExecutionMode {
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
        log.info('Artefact would be removed: {}', repoPath.toPath())
    }

}

/**
 * Класс, отвечающий за режим фактического удаления
 */
final class DeleteExecutionMode extends ExecutionMode {
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
        log.info('Artefact has been removed: {}', repoPath.toPath())
    }

}

/**
 * Интерфейс Comparator определяет единственный метод для сравнения двух длинных чисел.
 **/
interface Comparator {
    Boolean compare(Long first, Long second)
}

/**
 * Класс InnerCompare реализует интерфейс Comparator. Метод compare возвращает true,
 * если первое число больше второго.
 **/
final class InnerCompare implements Comparator {
    Boolean compare(Long first, Long second) {
        return first > second
    }
}

/**
 * Класс OuterCompare реализует интерфейс Comparator. Метод compare возвращает true,
 * если первое число меньше второго.
 **/
final class OuterCompare implements Comparator {
    Boolean compare(Long first, Long second) {
        return first < second
    }
}

/**
 *
 *
 **/
final class TrueCompare implements Comparator {
    Boolean compare(Long first, Long second) {
        return true
    }
}

/**
 *
 *
 **/
final class FalseCompare implements Comparator {
    Boolean compare(Long first, Long second) {
        return false
    }
}

/**
 *
 *
 **/
class SkipObject {
    List<String> repos
    Map<String, List<String>> directories

    SkipObject(List<String> repos, Map<String, List<String>> directories) {
        this.repos = repos
        this.directories = directories
    }
}


/**
 * Фабричный метод для создания объекта ExecutionMode. Создает объект
 * DryExecutionMode или DeleteExecutionMode в зависимости от параметра isDryMode.
 *
 * @param isDryMode Boolean значение, указывающее, следует ли использовать DryExecutionMode.
 * Если true, будет создан DryExecutionMode, в противном случае - DeleteExecutionMode.
 * @param repositories ссылка на объект repositories, содержащий информацию о репозиториях.
 * @param log ссылка на объект log, используемый для логирования действий.
 * @return возвращает новый объект ExecutionMode.
 */
static ExecutionMode createExecutionMode(Boolean isDryMode, def repositories, def log) {
    return isDryMode ? new DryExecutionMode(repositories, log) : new DeleteExecutionMode(repositories, log)
}

/**
 * Фабричный метод для создания объекта Validator. Создает объект
 * LastModifiedDayIntervalValidator или LastDownloadedIntervalValidator в зависимости от параметра validatorType.
 *
 * @param validatorType String значение, указывающее тип валидатора.
 * Может быть 'lastmodified' или 'lastdownloaded'. При любых других значениях, будет использоваться
 * LastModifiedDayIntervalValidator по умолчанию.
 * @param interval Long значение, указывающее интервал для валидатора.
 * @param repositories ссылка на объект repositories, содержащий информацию о репозиториях.
 * @param log ссылка на объект log, используемый для логирования действий.
 * @return возвращает новый объект Validator.
 */
static Validator createValidator(String validatorType, Long interval, Comparator comparator, def repositories, def log) {
    log.info("Validator Type: $validatorType")
    String lowerValidatorType = validatorType.toLowerCase()
    switch (lowerValidatorType) {
        case 'lastmodified':
            return new LastModifiedDayIntervalValidator(interval, comparator, log, repositories)
        case 'lastdownloaded':
            return new LastDownloadedIntervalValidator(interval, comparator, log, repositories)
        default:
            def errorMessage = "$validatorType Not valid value, use default valitator"
            log.info(errorMessage)
            return new LastModifiedDayIntervalValidator(interval, comparator, log, repositories)
    }
}

/**
 * Фабричный метод для создания экземпляра Comparator.
 * В зависимости от переданного типа (строки 'inner' или 'outer'), создаётся экземпляр
 * соответствующего класса. Если передан неизвестный тип, метод выводит сообщение об ошибке
 * и возвращает экземпляр класса InnerCompare по умолчанию.
 *
 * @param comparatorType строка, определяющая тип сравнивателя ('inner' или 'outer').
 * @param log объект для логирования сообщений.
 * @return экземпляр класса, реализующего интерфейс Comparator.
 * */
static Comparator createComparator(String comparatorType, def log) {
    switch (comparatorType) {
        case 'inner':
            return new InnerCompare()
        case 'outer':
            return new OuterCompare()
        case 'all':
            return new TrueCompare()
        case 'none':
            return new FalseCompare()
        default:
            log.info("$comparatorType Not valid value, use default Inner Comparator")
            return new InnerCompare()
    }
}
