# OpenCode × Nokia 6300 — Bluetooth-пульт голосового управления

Управляйте [OpenCode](https://opencode.ai) с настоящего Nokia 6300
(Series 40, MIDP 2.0 / CLDC 1.1) по Bluetooth: запишите голосовую заметку
на телефоне → Groq Whisper расшифрует её → промпт выполняется в вашей
сессии OpenCode → ответ транслируется в реальном времени на экран 240×320.

```
Телефон (J2ME MIDlet) --[BT SPP / TCP]--> мост на ПК --> Groq Whisper --> OpenCode
       ^------------------------------------------------------------------|
              статус, строки чата, активность инструментов — вживую
```

## Быстрая настройка

### Linux (Debian/Ubuntu/Arch/Fedora/Void — определяется автоматически)

```sh
./setup.sh
```

### macOS

```sh
./setup.sh          # тот же скрипт; определяет Darwin и использует Homebrew
```

### Windows

```powershell
.\setup.ps1
```

Скрипт настройки устанавливает **bun**, **ffmpeg**, **JDK 8**,
**ProGuard** (для CLDC preverify) и JS-зависимости, затем печатает
следующие шаги. Всё ставится локально — root/sudo нужен только для
вызова пакетного менеджера.

### Что скрипт настройки *не* делает

* Не связывает телефон (сделайте один раз: на телефоне Bluetooth
  **Включён + Видимый**, затем `scripts/void-bt-setup.sh` на Linux
  либо пара из настроек Bluetooth на macOS/Windows).
* Не задаёт `GROQ_API_KEY` (получите ключ на console.groq.com, затем
  `export GROQ_API_KEY=gsk_…`).

## Запуск стека

```sh
export GROQ_API_KEY=gsk_...
./run-all.sh        # мост (bun) + BT-прокси (node) + веб-панель
```

* Панель: http://127.0.0.1:4097/nokia
* Инициатива от телефона: на 6300 откройте **OpenCode Remote → Connect**
  (прямой набор ПК, поиск не нужен).
* Инициатива от ПК: на 6300 выберите **Wait for PC**, затем
  `NOKIA_BT_ADDR=00:22:66:C8:4C:07 node scripts/bt-connect.js`.

Нет Bluetooth на ПК (или тестируете в эмуляторе)? Мост всегда слушает
**TCP :19741** — укажите его в JAD-свойстве `TcpHost` телефона или
тестируйте через MicroEmulator по `socket://localhost:19741`.

## Сборка телефонного приложения

Нужны: JDK 8 (поддержка `-source 1.3`), ProGuard ≥ 7.4 (preverify),
`jar`. Всё это ставит `setup.sh`.

```sh
./scripts/build-jar.sh     # заглушки -> javac -> preverify -> jar/jad -> OBEX-загрузка
```

Результат — в `NokiaOpenCode/bin/` (`NokiaOpenCode.jar` + `.jad`).
Предсобранные бинарники уже лежат в этом репозитории — можно пропустить
сборку и сразу загрузить их по OBEX:

```sh
obexftp --bluetooth <PHONE_MAC> --put NokiaOpenCode/bin/NokiaOpenCode.jad
obexftp --bluetooth <PHONE_MAC> --put NokiaOpenCode/bin/NokiaOpenCode.jar
```

На телефоне: открыть `.jad` → Установить → разрешить Bluetooth и
микрофон. На S40: Менеджер приложений → OpenCode Remote → Разрешения →
Bluetooth/Мультимедиа → **Всегда разрешать**.

## Структура

```
NokiaOpenCode/            MIDlet для Java ME (Nokia 6300)
  src/com/nokia/opencode/   MIDlet, BTClient, AudioRecorder, Protocol, Ui
  stub-src/                 заглушки MIDP/JSR-82/MMAPI (classpath для сборки)
  res/                      логотипы PNG
  bin/                      предсобранный preverified jar + jad
  build.xml                 сборка через WTK / Antenna (альтернатива)
opencode-nokia-plugin/    мост + плагин OpenCode на ПК (сервер + TUI + web)
  src/bridge.ts             BT SPP + TCP + Groq Whisper + OpenCode-клиент
  src/index.ts              серверный плагин (tool, command, RPC, lifecycle)
  src/tui.tsx               TUI-плагин (статус-строка, route, палитра, toasts)
  src/rpc.ts                общий контракт RPC (id "nokia")
  web/dashboard.html        веб-панель без зависимостей, в реальном времени
  standalone-bridge.mjs     запуск моста отдельно (без загрузчика плагина)
scripts/
  build-jar.sh              полная сборка телефонного приложения + загрузка
  run-all.sh                запуск моста + прокси + панели
  bt-proxy.js               мост BT SPP -> TCP :19741
  bt-connect.js             исходящий набор с ПК (режим "Wait for PC")
  void-bt-setup.sh          помощник пара/доверия для BlueZ (Linux)
setup.sh                    одноразовая настройка для Linux/macOS
setup.ps1                   одноразовая настройка для Windows
```

## Протокол связи (RFCOMM SPP, UTF-8 строки через \n)

Телефон → ПК:

```
HELLO Nokia6300 MIDlet-1.0
VOICE:BEGIN <totalBytes>
VOICE:DATA <base64-чанк, ~512 символов = 384 байта аудио>
VOICE:END
TEXT:<промпт с клавиатуры>
CANCEL
PING
BYE
```

ПК → телефон:

```
HELLO OpenCode-Bridge 1.0
STATUS <короткая строка статуса>
PROGRESS <этап>|<проценты 0-100>|<подпись>
YOU <строка расшифровки>
TEXT <строка ответа>
TOOL <активность инструмента>
DONE
ERROR <сообщение>
PING
```

Этапы: `RECEIVING TRANSCRIBING SENDING WORKING DONE ERROR`.
Аудио — AMR-NB (MMAPI `capture://audio?encoding=amr`), максимум 30 с.

## Web и TUI

Серверный плагин регистрирует RPC с id `nokia`:

```sh
opencode api post /api/rpc/nokia/status --data '{}'
opencode api post /api/rpc/nokia/sendText --data '{"text":"перечисли файлы"}'
```

TUI-плагин добавляет строку статуса в сайдбаре, route `nokia`,
команды палитры (`nokia.status`, `nokia.send`, `nokia.cancel`) и
уведомления. Веб-панель от моста: `http://127.0.0.1:4097/nokia`.

## Решение проблем

| Проблема | Решение |
|---|---|
| `br-connection-unknown` при подключении | Bluetooth телефона выключен / вне зоны. Включите BT + видимость, держите телефон в 5 м, повторите. |
| Телефон не находит `OpenCode-Nokia` | Перезапустите BT-настройку (видимость истекает примерно через 180 с). |
| Ошибки `GROQ_API_KEY` | `export GROQ_API_KEY=gsk_…` перед запуском. |
| Groq отклоняет AMR | Установите `ffmpeg` — мост сам конвертирует AMR→WAV. |
| Сборка `bluetooth-serial-port` падает | Нормально для некоторых платформ. Мост переключается на TCP `:19741`. Поставьте тулчейн (в Linux — `bluez` + заголовки, в macOS — Xcode CLT, в Windows — VS Build Tools) и повторите `npm install bluetooth-serial-port`. |
| `SecurityException` в MIDlet на BT/микрофоне | При установке ответьте «Всегда разрешать»; на S40: Разрешения → Bluetooth/Мультимедиа → Всегда разрешать. |

## Лицензия

MIT — см. LICENSE.
