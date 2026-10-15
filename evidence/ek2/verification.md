# Протокол ЭК2 — 15 октября 2026 года

Объект: Helpdesk, код кандидата `5ac6a7f6405a50af0d80e20f091716154c584d09`. До изменения — `774dedd1d92e786e39019fbe5188f12ef15e365c`. Проверки выполнялись инструментами Codex на локальном компьютере; независимого проверяющего не было.

## Версии и идентификация

[Версии инструментов и базы](tools.json), [исходники и хэши](source-manifest.json), [параметры среды](runtime.json). Trivy 0.76.0 использовал одну базу с `UpdatedAt=2026-10-15T07:10:08.755380281Z` для сравнения до/после. База не включена в Git; при новом скачивании результаты могут измениться.

JDK локальной сборки — Corretto 17.0.8; в финальном контейнере — Temurin 17.0.20.1. Проверена Linux/ARM64 среда Colima; успешный повтор на другой архитектуре не заявляется. Инструменты загружены из [официального Trivy v0.76.0](https://github.com/aquasecurity/trivy/releases/tag/v0.76.0) и [Gitleaks v8.30.1](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1); SHA-256 архивов сверены с digest опубликованных GitHub release assets и сохранены в tools.json. Для другой ОС нужен её архив и соответствующая контрольная сумма.

Хэш локального JAR может отличаться от JAR Docker-сборки: используются разные JDK и упаковка. Итоговый `sbom-runtime.cdx.json` построен непосредственно из `/app/app.jar` проверенного образа, а не из предположения о его составе. Пары координат и хэши каждой упакованной библиотеки сохранены. Это плоский состав приложения, а не восстановленный граф всех транзитивных связей. Стартеры и BOM, не упакованные в JAR, исключены; добавленный Spring Boot Jarmode Tools учтён. В исходном JAR 49 библиотек, в кандидате 50.

## M1–M3: код, доступ и тесты

Порядок ручного просмотра: входные параметры → действующее лицо → проверка роли/объекта → SQL → возвращаемые поля/аудит. Проверена следующая область:

| Область | Файлы и вывод | Дополнительная проверка |
| --- | --- | --- |
| Подстановка ID и чужие записи | `TicketController.kt`, `TicketService.kt`: actor берётся из Principal и БД; USER сверяется с requester, AGENT с assignee; список фильтруется на SQL-уровне | `foreign owner cannot read list or ticket`, `foreign owner cannot mutate ticket`, `unassigned agent cannot read detail or write` |
| Внутренние заметки | `details`: условие internal применяется до LIMIT/OFFSET; очередь возвращает только QueueItem | `internal notes never reach requester`, `private notes are filtered before pagination` с 55 закрытыми и 51 публичным сообщением |
| Смена специалиста | `reopen` очищает assignee; новый claim назначает нового специалиста, после чего старый не проходит requireAccess | `reassignment transfers history but revokes former agent access` |
| SQL и транзакции | Значения привязаны параметрами; динамическая колонка выбирается только из двух серверных констант; чтение/назначение/запись защищены транзакцией и блокировкой строки | `sql fragments are stored as data`, конкурентный claim: один успех, один 409, одна запись аудита |
| Подмена полей | DTO разрешают только title/description/body; неизвестные JSON-поля запрещены | Параметризованные тесты лишних полей, роли и internal=true |
| Сессии и вход | CSRF не отключён, request cache отключён, фильтр лимита после CSRF и до проверки пароля; forwarded headers не доверены | Интеграционные тесты и настоящий HTTP ниже |
| Ошибки и секреты | `ApiErrors.kt`, `SecurityConfig.kt`, `DemoUsers.kt`, Compose/Docker: ответы без stack trace; аудит пишет ID и action, не переписку; `.env` исключён из Git/образа | Gitleaks, HTTP 400, проверка `/api/me`; полного динамического аудита всех логов нет |

Тесты с настоящим PostgreSQL, HTTP-слой в основном через MockMvc:

```sh
# Для Colima:
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew clean test bootJar cyclonedxBom --no-daemon
```

[До](tests-before.json): 28/28; [после](tests-after.json): 37/37, без ошибок и пропусков. Новые проверки покрывают ограничитель (5 unit-тестов, включая одновременные запросы), его место в цепочке безопасности (2 интеграционных) и два соседних сценария доступа. Тесты не заменяют весь анализ кода, сетевой нагрузочный тест или независимый аудит.

Gitleaks проверяет историю до кода `5ac6a7f` и отдельно содержимое всего публикуемого снимка, включая новые документы и свидетельства:

```sh
gitleaks git . --redact=100 --report-format json --report-path build/security/secrets-history.json --no-banner
# Для полностью зафиксированного комплекта; временную папку создайте вне репозитория:
mkdir -p ../helpdesk-scan
git archive HEAD | tar -x -C ../helpdesk-scan
gitleaks dir ../helpdesk-scan --redact=100 --report-format json --report-path build/security/secrets-current.json --no-banner
```

Наличие находок даёт ненулевой код выхода; оно требует разбора, а не автоматического признания утечки. [История](secrets-history.json), [снимок](secrets-current.json), [триаж](secrets-triage.json). Исходный сигнал на `ApiErrors.kt` проверен командой `git show f58554c:src/main/kotlin/ru/course/helpdesk/ApiErrors.kt | shasum -a 256`: это хэш файла. В текущем снимке обнаружены два сигнала на тот же хэш в старом и новом манифестах; оба проверены пересчётом. Реальные локальные пароли в снимок и отчёты не включены. Подавления всего класса generic-api-key не добавлено.

## M4: состав и анализ компонентов

```sh
mkdir -p build/security
./gradlew bootJar cyclonedxBom --no-daemon
python3 scripts/package_sbom.py --output build/security/sbom.cdx.json
trivy sbom --format json --output build/security/dependencies.json build/security/sbom.cdx.json
trivy image --pkg-types os --skip-files '**/*.jar' --scanners vuln --format json \
  --output build/security/image.json course-helpdesk-app
trivy image --pkg-types os --skip-files '**/*.jar' --scanners vuln --format json \
  --output build/security/postgres.json postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24
```

Для состава именно Docker-поставки сначала выполните `./scripts/compose.sh cp app:/app/app.jar build/security/container.jar`, затем передайте `--jar build/security/container.jar` в `package_sbom.py`. Перед этим `cyclonedxBom` должен быть получен из той же версии кода и lock-файла.

При сравнении использовался отдельный cache-dir Trivy и `--skip-db-update` после единственного обновления базы. Ошибка загрузки базы означает неполную проверку. SCA Java шёл по SBOM, анализ ОС — отдельно; первый неуспешный запуск общего сканирования из-за отсутствующей Java-базы не засчитан.

| Область | До | После | Интерпретация |
| --- | --- | --- | --- |
| Java, фактически упакованные библиотеки | 18: 5 Critical, 6 High, 7 Medium | 2 Critical по сканеру | 16 сигналов устранены обновлением; 2 неприменимы к текущим функциям по первоисточникам |
| ОС приложения | 78: 2 High, 34 Medium, 42 Low | 52: 20 Medium, 32 Low | 26 сигналов устранены. Для оставшихся база не указывает исправленную версию; применимость не доказана и не опровергнута |
| ОС PostgreSQL | 1 Medium | 1 Medium | Образ не менялся. zlib требует дальнейшего разбора/обновления; исключение E03 |

[Отчёт Java до](dependencies-before.json), [после](dependencies-after.json), [ОС до](image-before.json), [после](image-after.json), [PostgreSQL](postgres-before.json), [построчный триаж](triage.json). Ни 52 сигнала ОС, ни их сокращение не следует трактовать как число уязвимых HTTP-операций. Обновлены Jackson 2.21.4 → 2.21.7, Tomcat 10.1.55 → 10.1.61, pgJDBC 42.7.11 → 42.7.14, Log4j API 2.24.3 → 2.25.5; Java 17 и Spring Boot 3.5 сохранены. Дополнительно закреплены обновлённые базовые образы и три пакета ОС в Dockerfile.

Не покрыты полностью Gradle-плагины, тестовые библиотеки, JDK как отдельный продукт, Docker-хост и неизвестные уязвимости. Успешный SCA не является анализом бизнес-логики.

## M5: реальные HTTP-запросы и сравнение

После свежего запуска приложения выполнены:

```sh
python3 scripts/login_probe.py --expect limited --output build/security/login.json
python3 scripts/smoke.py
python3 scripts/http_security_probe.py --output build/security/http.json
```

[12 сценариев HTTP](http-security.json) проверяют cookie, CSRF входа и изменения, успешный вход, смену идентификатора, отказ старой cookie, отсутствие разрешения CORS чужому Origin, nosniff, безопасную ошибку JSON, chunked-тело больше 32 КиБ, выход и отказ завершённой сессии. CORS не считается авторизацией. Настроенные 15 минут не проверялись ожиданием; HTTPS не разворачивался.

Сопоставимый сценарий [до](login-before.json)/[после](login-after.json): 12 неправильных паролей, один адрес, свежий процесс, затем правильный пароль (после ожидания Retry-After для новой версии). До — 12×401; после — 10×401 и 2×429. Подстановка X-Forwarded-For не обходит лимит, после окна получен 204. Сохранённые отчёты не содержат токенов, cookie и паролей.

Для повторения исходного состояния клонируйте репозиторий отдельно в домашнюю папку, переключитесь на `774dedd`, скопируйте туда **только** актуальный `scripts/login_probe.py`, создайте новый `.env` и запустите Compose с отдельными `-p helpdesk-ek2-before` и `APP_PORT=18081`. Выполните сценарий с `--base-url http://127.0.0.1:18081 --expect unlimited`. Затем остановите только этот отдельный проект. Не переключайте рабочую БД на старый код и не удаляйте основной том ради сравнения.

## M6–M7: чистая копия и среда

Код `5ac6a7f` клонирован локально без рабочего `.env`. Новые секреты созданы `init_env.py`; тесты прошли с отдельным PostgreSQL Testcontainers. Для запуска использованы новый том и проект `helpdesk-ek2-clean`, порт 18080. После smoke создано одно обращение; перезапуск сохранил его, в истории Flyway осталась одна успешная V1. Результат — [clean-checkout.json](clean-checkout.json).

Первая копия лежала в `/private/tmp`, который не был подключён к Colima: скрипт bind mount оказался каталогом, PostgreSQL завершился с ошибкой. Копия без изменения исходников перенесена в домашний каталог; удалён только временный неуспешный том. Повтор прошёл. Ограничение каталога теперь указано в README; этот отказ не скрыт как успешный первый запуск.

[Runtime](runtime.json) подтверждает UID 10001, read-only root, drop ALL capabilities, no-new-privileges, loopback приложения, отсутствие публикации порта БД. Роль `helpdesk` без superuser/createdb/createrole, но владеет таблицами. Сохранение тома не проверяет восстановление резервной копии.

Полные переменные окружения из `docker inspect` не публиковались. Очистка тестовой среды относится только к `helpdesk-ek2-clean`; исходный том `course-helpdesk_db-data` сохраняется. Чистая копия — отдельный запуск тем же исполнителем, а не независимая проверка другим человеком.
