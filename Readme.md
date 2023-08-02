# Скрипт очистки артефактов Artifactory

## Обзор

Скрипт предоставляет автоматизированный способ очищать репозитории на основе различных политик, которые определены в JSON-файле конфигурации. 
Его можно выполнить вручную через REST API или автоматически, используя запланированное задание. 

Этот плагин обладает следующими режимами работы:

1. Режим dry - это пробный прогон. Вся логика обрабатывается, но удаление не выполняется.
2. Режим execute - это рабочий режим. Удаление выполняется в соответствии с предоставленной логикой.

Реализованы слудующие валидаторы:
1. LastModifiedIntervalValidator - позволяет проводить проверку на основе даты последнего изменения артефакта.
2. LastDownloadedIntervalValidator - позволяет проводить проверку на основе даты последней загрузки артефакта.
## Установка
Для использования скрипта его нужно установить в директорию ${ARTIFACTORY_HOME}/etc/artifactory/plugins экземпляра Artifactory. 
Файл конфигурации скрипта должен быть размещен в той же директории и иметь имя rotationPlugin.json. После переноса плагина и конфигурации 
необходимо обновить список плагинов artifactory.

```bash
curl -XPOST http://USERNAME:PASSWORD@HOSTNAME:PORT/artifactory/api/plugins/reload
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
    <logger name="rotationPlugin">
        <level value="info"/>
    </logger>
```


## Использование
### Запуск через API
Необходимо создать json файл с параметрами для API.
```json
{ "dryRun": true, "interval": 1, "exclude": ["Library", "build"] }
```
Далее необходимо обратиться по API.
```bash
curl -XPOST http://USERNAME:PASSWORD@HOSTNAME:PORT/artifactory/api/plugins/execute/rotateRegular -T properties.json
```

### Запуск по cron job
Стандартный способ запуска плагина. Для запуска необходимо определить политики запуска.  
Политика содержит следующие поля:   
_cron_ <b>String</b> - Формат для cron Quartz scheduler в Java    
_mode_ <b>String</b> - Варианты выполнения плагина. Поддерживаются два варианта: _exclude_ и _include_. В режиме **exclude**, плагин обрабатывает все локальные репозитории, но исключает из общего списка репозиториев те, которые указаны в переменной _repos_. В режиме **include**, плагин использует только репозитории, которые были переданы в переменной _repos_.
Режим <b>include</b> использует только репозитории, которые были переданы в переменной repos.   
_validator_ **String** - Тип валидатора. Доступные валидаторы: _LastModified_ и _LastDownloaded_.  
_repos_ **List<String>** - Список репозиториев.   
_interval_ **Integer** - Интервал в днях.  
_dryRun_ **Boolean** - Указывает, нужно ли использовать тестовый режим ("true" - да, "false" - нет).    

```json
{
    "policies": [
        {
            "cron": "0 0 1 ? * 1",
            "mode": "exclude",
            "validator": "LastModified",
            "repos": ["Library", "build" ],
            "interval": 30,
            "dryRun": true
        },
        {
            "cron": "0 0 1 ? * 1",
            "mode": "include",
            "validator": "LastDownloaded",
            "repos": ["tmp" ],
            "interval": 30,
            "dryRun": true
        }
    ]
}
```
## Развитие
### Создание валидаторов
Для создания валидатора необходимо:
1. Расширьте абстрактный класс **_ArtifactoryContext_** и реализуйте интерфейс **_Validator_**.
2. Дополните фабричную функцию **_createValidator_** для создания экземпляра вашего валидатора.
3. Расширьте список доступных валидаторов в функции валидации **_validateConfigObject_**.
