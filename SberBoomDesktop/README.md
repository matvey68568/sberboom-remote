# SberBoom Remote для Windows

Десктоп-версия того же пульта: локальное управление колонкой SberBoom по Wi-Fi
(`wss://<ip>:20000`), без Home Assistant, облака и аккаунта Сбера.

## Что общего с Android-версией

Приложение — не переписанная копия, а вторая сборка того же кода:

```
D:\sberboom-app\
├── shared\                     общий слой (компилируется в обе версии)
│   └── src/main/java/ru/sberboom/remote/
│       ├── protocol\           TLV-кодек, конверт, команды, парсеры (op=10/12/17/19)
│       ├── net\                SpeakerClient (WSS), ZvukApi (Звук), LyricsApi
│       └── platform\           Settings / DiscoveryService / CoverService — интерфейсы
├── SberBoomRemote\             Android-приложение (Gradle + AGP 9 + Compose)
└── SberBoomDesktop\            Windows-приложение (Gradle + Compose Desktop)
```

Android подключает общий слой как модуль `:shared` (`settings.gradle.kts` →
`project(":shared").projectDir = file("../shared")`), Windows — как дополнительный
`srcDir` в Kotlin-sourceSet. Правки протокола делаются один раз и попадают в обе версии.

Платформенные отличия — только в трёх реализациях:

| Интерфейс | Android | Windows |
|---|---|---|
| `Settings` | SharedPreferences | `java.util.prefs` (реестр HKCU) |
| `DiscoveryService` | `NsdManager` + TCP-скан | JmDNS + TCP-скан `/24` |
| `CoverService` | `BitmapFactory` + `android.graphics.Color` | `ImageIO` + `java.awt.Color` |

Обложки в UI: coil `AsyncImage` на Android, свой `NetImage` (Skia + OkHttp) на Windows.

- **Трей-иконка**: окно скрывается в трей, оттуда доступны пауза и переключение треков
- **Меню приложения**: состояние, сопряжение заново, обновление
- **Горячие клавиши**: `Ctrl+Space` — пауза, `Ctrl+←/→` — трек, `Ctrl+↑/↓` — громкость,
  `Ctrl+M` — mute, медиа-клавиши клавиатуры
- **Таймер сна, «Недавнее» и диагностика** — те же экраны, что на Android


## Установка на Windows

Два способа, оба без прав администратора не обязательны:

| Файл | Что делает |
|---|---|
| `SberBoomRemote-Windows-1.x.msi` | обычный установщик: ярлык в меню «Пуск», пункт удаления в «Программы и компоненты», встроенная JVM |
| `SberBoomRemote-Windows-1.x.zip` | портативная версия: распаковать и запустить `SberBoom Remote.exe` |

SmartScreen может предупредить о неизвестном издателе — подпись не настроена, файлы
собраны локально.

### Сборка MSI

jpackage собирает MSI только через WiX Toolset 3.14 (v4 не поддерживается). Установка
в систему не обязательна — достаточно портативных бинарников:

```bash
# один раз: WiX 3.14 в ../tools/wix
curl -L -o wix.zip https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip
unzip wix.zip -d ../tools/wix

bash tools/build-msi.sh     # соберёт build/compose/binaries/main/msi/*.msi
```

Скрипт выставляет `WIX` и `PATH` так, чтобы `light.exe` находил `WixUtilExtension`
(иначе jpackage падает с кодом 311), и требует полный JDK с `jpackage.exe`.

Важно: описание пакета в `build.gradle.kts` — латиницей. Русский текст в метаданных
MSI приводит к ошибке `light.exe` (код 311, недопустимые символы для кодовой страницы).

## Сборка

```bash
cd D:\sberboom-app\SberBoomDesktop
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

./gradlew run                 # запуск из исходников
./gradlew createDistributable # приложение с встроенной JVM в build/compose/binaries/main/app
```

`createDistributable` требует полный JDK с `jpackage.exe` — JBR из Android Studio
это урезанный JRE. Скрипт берёт `../tools/jdk21` (Temurin 21) или путь из `SB_JDK`.

Готовый архив: `D:\sberboom-app\SberBoomRemote-Windows-1.0.zip` — распаковать и
запустить `SberBoom Remote.exe` (JVM внутри, установка не нужна).

`packageMsi` не используется: нужен WiX Toolset, которого на машине нет.

## Возможности

Те же, что на Android: плеер с обложкой и ambient-цветом, громкость/mute/seek,
shuffle/repeat, лайк/дизлайк, скорость 0.5×–2.0×, поиск музыки и плейлистов Звука,
каталог с drill-down (артист → альбом → трек), очередь, Bluetooth-панель,
караоке с синхронным текстом и посимвольной заливкой, панель «Колонка»
(модель/производитель/прошивка/серийник из `op=10`, задержка связи по эхо `op=8`,
будильники, таймеры, стереопара, multiroom, сеть), несколько колонок со своими токенами.

## Особенности Windows-запуска

- Сопряжение то же: колонка просит нажать **«+»** на корпусе, токен сохраняется в реестр.
- Токен живёт до следующего сопряжения — если его отозвали (например, сопряглись с
  телефона), приложение само забывает его и просит новое «+».
- Windows SmartScreen может предупредить о неизвестном издателе — подпись не настроена,
  архив собран локально.
