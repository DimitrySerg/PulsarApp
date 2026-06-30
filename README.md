# Pulsar Modbus — Android-приложение для обмена с прибором «Пульсар»

## Важное ограничение платформы

В Android **нет нативного COM-порта**, как в Windows (`CreateFileA("COM3")`/`SetCommState`/`WriteFile`/`ReadFile`
из вашего `pulsar.cpp`). Чтобы физически передавать данные по последовательному интерфейсу,
телефон/планшет должен поддерживать **USB-OTG** (host-режим), а к нему подключается
**USB↔RS232/RS485 адаптер** (FTDI, CP210x, CH340/CH341, PL2303 либо встроенный CDC-ACM).
Само приложение работает с таким адаптером через библиотеку `usb-serial-for-android`,
которая выполняет ту же роль, что Win32 API в исходном коде.

Если интерфейс прибора — RS-485, используется адаптер USB↔RS485 (тот же usb-serial-for-android,
просто иначе разводка по RX/TX/направлению на самом адаптере).

## Что реализовано

1. **Название COM-порта** — список найденных USB-serial адаптеров (Spinner), кнопка
   «Обновить» (повторный поиск устройств) и «Открыть»/«Закрыть» соединение.
2. **Параметры передачи** — скорость (1200…115200), чётность (нет/нечётная/чётная),
   адрес прибора (`devAddr`), количество повторов запроса и тайм-аут ответа — всё это
   прямой аналог полей `pulsar_params_t` (`baud_rate`, `parity`, `resp_num`, `ans_timeout`).
3. **Таблица параметров и каналов** — загружается из `pulsar_parameters_channels.xlsx`,
   данные были выгружены в `app/src/main/assets/pulsar_data.json` (113 параметров + 14 каналов,
   с полями: номер, название, имя переменной, адрес, тип данных, мин/макс, уровни доступа).
   Есть фильтр/поиск по названию.
4. **Чтение/запись** — у каждой строки таблицы: чекбокс выбора, поле значения, кнопки
   «Читать» / «Записать» — для одиночной операции. Кнопки внизу экрана: «Выбрать все»,
   «Читать выбранные», «Записать выбранные», «Читать всё» — для групповых операций.
5. **Протокол обмена** — `PulsarProtocol.kt` — прямой порт логики из `pulsar.cpp`/`pulsar.h`:
   - CRC16 (Modbus, poly `0xA001`, init `0xFFFF`) — `pulsar_crc()`;
   - формат пакета `[addr 4B BE][func][len][payload][id 0x01,0x00][crc16 LE]` — `pulsar_encode_packet()`;
   - разбор ответа с пропуском «мусорных» байт при несовпадении CRC — `pulsar_parse_packet()`;
   - функции протокола: `FUNC_READ`(0x0A)/`FUNC_WRITE`(0x0B) для параметров,
     `CHAN_READ`(0x01)/`CHAN_WRITE`(0x03) для каналов — как в `pulsar.h`;
   - коды ошибок и их расшифровка — `pulsar_decode_error()`.
   Логика повторных запросов при тайм-ауте (`resp_num`/`ans_timeout`) реализована в `PulsarTransport.transaction()`.
6. **Страница «Отладка»** (кнопка «Отладка» на главном экране) — журнал всех транзакций
   с отправленными и принятыми байтами в hex, статусом (ОК/код ошибки), временем и
   названием операции; автообновление, кнопка «Очистить». Реализация: `TransactionLog.kt`
   (хранилище в памяти процесса), `DebugActivity.kt` + `LogAdapter.kt` (экран). Логирование
   подключено прямо в `PulsarTransport.transaction()` — пишется каждая попытка обмена,
   включая тайм-ауты и ошибки порта.

## Сборка APK — выполните самостоятельно

В этой среде (где собран код) нет доступа к Android SDK и к репозиториям Google Maven /
Gradle (сеть ограничена белым списком доменов без `dl.google.com` / `maven.google.com` /
`services.gradle.org`), поэтому собрать готовый `.apk` здесь невозможно. Соберите его
у себя одним из способов:

**А. Android Studio (проще всего)**
1. File → Open → выберите папку `PulsarApp`.
2. Дождитесь синхронизации Gradle.
3. Build → Build Bundle(s)/APK(s) → Build APK(s). Готовый файл будет в
   `app/build/outputs/apk/debug/app-debug.apk`.

**Б. Командная строка (если установлены Android SDK + Gradle)**

В проект уже добавлены сами скрипты `gradlew` / `gradlew.bat` и `gradle/wrapper/gradle-wrapper.properties`
(версия Gradle 8.2). Не добавлен только бинарный `gradle/wrapper/gradle-wrapper.jar` — его
нельзя сгенерировать в среде, где я собираю проект (нет доступа к `services.gradle.org`).
Получить его — один раз, перед первой сборкой:

```
cd PulsarApp
gradle wrapper --gradle-version 8.2   # если Gradle уже установлен в системе
# или просто откройте проект в Android Studio один раз — она создаст gradle-wrapper.jar сама
./gradlew assembleDebug
```
Результат: `app/build/outputs/apk/debug/app-debug.apk`.

```
PulsarApp/
├── app/
│   ├── build.gradle                     зависимости (usb-serial-for-android, coroutines, recyclerview)
│   └── src/main/
│       ├── AndroidManifest.xml          разрешение android.hardware.usb.host
│       ├── assets/pulsar_data.json      параметры и каналы из xlsx
│       ├── java/com/pulsar/app/
│       │   ├── MainActivity.kt          UI и сценарии работы
│       │   ├── PulsarProtocol.kt        CRC16, кодирование/разбор пакета (порт pulsar.cpp)
│       │   ├── PulsarTransport.kt       USB-serial соединение + транзакции (порт pulsar_transaction)
│       │   ├── ValueCodec.kt            кодирование/декодирование значений по типу данных
│       │   ├── ParamItem.kt             модель строки таблицы
│       │   ├── ParamRepository.kt       загрузка pulsar_data.json
│       │   ├── ParamAdapter.kt          RecyclerView-адаптер таблицы
│       │   ├── TransactionLog.kt        журнал TX/RX для страницы «Отладка»
│       │   ├── DebugActivity.kt         экран «Отладка»
│       │   └── LogAdapter.kt            адаптер списка журнала
│       └── res/...                      разметка экрана и темы
├── build.gradle / settings.gradle / gradle.properties
```

## Сборка

1. Откройте папку `PulsarApp` в Android Studio (Hedgehog/Iguana или новее) — Gradle wrapper
   будет сконфигурирован автоматически при первом открытии (либо выполните
   `gradle wrapper` вручную, если у вас уже установлен Gradle 8.2+).
2. Дождитесь синхронизации Gradle (зависимость `com.github.mik3y:usb-serial-for-android`
   подключается через JitPack — репозиторий уже указан в `settings.gradle`).
3. Соберите `Run ▶` на устройство Android с USB-OTG, к которому подключён USB↔RS232/RS485
   адаптер с прибором.

## Что нужно донастроить под конкретный объект

- **Карта типов данных**: типы `user8/user48/user64` сейчас декодируются как ASCII-строка
  (это поля вроде «Идентификационное наименование ПО»); при необходимости скорректируйте
  в `ValueCodec.kt`.
- **`devAddr`**: в исходном коде это `int` (4 байта, big-endian) — в UI это поле «Адрес прибора».
  Если в вашей сети используется широковещательный адрес или другой формат — измените в
  `SerialSettings`.
- **Однобайтовая длина пакета** (`txBuf[5] = len`) скопирована «как есть» из `pulsar.cpp` —
  при пакетах с полезной нагрузкой больше ~245 байт (например, чтение архива большими
  блоками) это поле переполнится, как и в оригинальном коде. Для функций `ARCH_READ`/
  `JOUR_READ` при необходимости — расширьте кодек.
- **Список VID USB-адаптеров** в `res/xml/usb_device_filter.xml` — добавьте свой VID/PID,
  если используется не FTDI/CP210x/CH340/PL2303/CDC-ACM.
