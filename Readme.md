# Скрипт очистки артефактов Artifactory

## Обзор

Плагин предоставляет автоматизированный способ очищать репозитории на основе различных политик, которые определены в JSON-файле конфигурации. 

Этот плагин обладает следующими режимами работы:

1. Режим dry - это пробный прогон. Вся логика обрабатывается, но удаление не выполняется.
2. Режим execute - это рабочий режим. Удаление выполняется в соответствии с предоставленной логикой.

Реализованы следующие валидаторы:
1. LastModifiedIntervalValidator - позволяет проводить проверку на основе даты последнего изменения артефакта.
2. LastDownloadedIntervalValidator - позволяет проводить проверку на основе даты последней загрузки артефакта.

## Установка
Для использования скрипта его нужно установить в директорию ${ARTIFACTORY_HOME}/etc/artifactory/plugins экземпляра Artifactory. 
Файл конфигурации скрипта должен быть размещен в той же директории и иметь имя rotationPlugin.json. После переноса плагина и конфигурации 
необходимо обновить список плагинов artifactory.

```bash
curl -XPOST -u USERNAME:PASSWORD https://HOSTNAME:PORT/artifactory/api/plugins/reload
```

Также можно использовать скрипт для установки. Для запуска необходимо определить переменные в скрипте.
```bash
ARTIFACTORY_HOME=/var/opt/jfrog/artifactory
PROTO=
USERNAME=
PASSWORD=
HOSTNAME=
PORT=
```
Запуск скрипта
```bash
chomod +x setup.sh
./setup.sh
```

### Логирование

Для вывода сообщений в логи необходимо дополнить следующий файл: ${ARTIFACTORY_HOME}/etc/artifactory/logback.xml

```xml
<logger name="rotationPlugin" additivity="false">
    <appender-ref ref="ROTATION"/>
    <level value="debug"/>
</logger>

<appender name="ROTATION" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <File>${log.dir}/rotation-plugin.log</File>
    <encoder>
        <pattern>%date -- %m%n</pattern>
    </encoder>
    <rollingPolicy class="org.jfrog.common.logging.logback.rolling.FixedWindowWithDateRollingPolicy">
        <FileNamePattern>${log.dir.archived}/rotation-plugin.%i.log.gz</FileNamePattern>
    </rollingPolicy>
    <triggeringPolicy class="org.jfrog.common.logging.logback.triggering.SizeAndIntervalTriggeringPolicy">
        <MaxFileSize>250MB</MaxFileSize>
    </triggeringPolicy>
</appender>
```


## Использование

### Запуск по cron job
Стандартный способ запуска плагина. Для запуска необходимо определить политики запуска.  
Политика содержит следующие поля:   
#### Обязательно  
_cron_ **`String`** - Формат для cron Quartz scheduler в Java    
_mode_ **`String`** - Варианты выполнения плагина. Поддерживаются два варианта: _exclude_ и _include_. В режиме **exclude**, плагин обрабатывает все локальные репозитории, но исключает из общего списка репозиториев те, которые указаны в переменной _repos_. В режиме **include**, плагин использует только репозитории, которые были переданы в переменной _repos_.
Режим **`include`** - Использует только репозитории, которые были переданы в переменной repos.   
_validator_ **`String`** - Тип валидатора. Доступные валидаторы: _LastModified_ и _LastDownloaded_.  
_repos_ **`List<String>`** - Список репозиториев.   
_interval_ **`Integer`** - Интервал в днях.  
_dryRun_ **`Boolean`** - Указывает, нужно ли использовать тестовый режим ("true" - да, "false" - нет).    

#### Опционально
_directories_ **`Map<String, List<String>>`** - Используется для исключения отдельных папок в репозиториях. 
Если режим установлен на _exclude_, есть возможность дополнительно исключить определенные директории.

```json
{
  "policies": [
    {
      "_name": "Basic name",
      "_comment": "some comment",
      "cron": "0 */3 * ? * *",
      "mode": "exclude",
      "directories": {
        "tmp": [
          "books"
        ]
      },
      "validator": "LastModified",
      "repos": [
        "Library",
        "build"
      ],
      "interval": 2,
      "intervalType": "outer",
      "dryRun": true
    },
    {
      "cron": "0 0 1 ? * 1",
      "mode": "include",
      "validator": "LastDownloaded",
      "repos": [
        "tmp"
      ],
      "interval": 1,
      "intervalType": "outer",
      "dryRun": true
    }
  ]
}
```
## Развитие
### Создание валидаторов
Для создания валидатора необходимо:
#### Обязательно
1. Реализуйте интерфейс **_Validator_**.
#### Опционально
2. Дополните фабричную функцию **_createValidator_** для создания экземпляра вашего валидатора.
3. Расширьте список доступных валидаторов в функции валидации **_validateConfigObject_**.
