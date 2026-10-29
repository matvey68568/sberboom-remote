# Что внутри оригинального приложения «Салют» (APK 26.09.1.18324)

Разбор для ответа на вопрос: есть ли в официальном companion-приложении локальные
команды, которых нет в `sboom_ha` / SberBoom Remote.

APK: 183 МБ, 9926 файлов, 8 dex (13.7 + 9.7 + 10.3 + 12.4 + 7.2 + 9.7 + 16.1 + 5.9 МБ),
91 нативная библиотека, 597 907 уникальных ASCII-строк в dex.

## Найденные .proto

40 файлов, но все — служебные: `asr/*`, `vps/*`, `mtt/*`, `client_analytics.proto`,
`firebase/perf/v1/perf_metric.proto`, `google/protobuf/*` (+ копии в `src/`).
Схемы StarOS в текстовом виде нет — она собирается в javalite-классы внутри dex.

## Настройки колонки (главная находка)

Пакет `ru.sberbank.sdakit.companion.staros.domain.api.domain.settings.entity.StarosSettings`:

| Тип настройки | Что это |
|---|---|
| `BrightnessSetting`, `BrightnessSettingV2` | яркость LED-кольца/дисплея |
| `EqualizerSetting` + `UserPreset` | эквалайзер: пресеты + пользовательский |
| `PriorityStreamSetting` + `Value` | приоритет источника (что играет поверх чего) |
| `BluetoothAudioSetting` + `Status` | BT-аудио и его состояние |
| `BatteryLowPowerModeSetting` + `Value` | энергосбережение (у моделей с батареей) |
| `DownloadingTracksSetting` | офлайн-треки (скачивание внутрь колонки) |
| `OpenBetaSetting` | канал прошивки (OpenBeta) |
| `PlaylistInfoSetting` | инфо о плейлисте |
| `CheckboxSetting`, `ToggleSetting`, `RadioButtons`, `CardSetting`, `ButtonsRow`, `CopySetting`, `WebSetting`, `LandingSetting`, `OnboardingSetting` | серверный конструктор UI |

То есть экран настроек устройства целиком **описывается сервером** (`StarosSettingsResponse`),
а приложение только рендерит `StarosSettings`.

## Транспорт этих настроек — облако

- `staros/domain/impl/data/api/StarosDevicesNetworkApi`, `CSAFrontNetworkApi`
- DTO на kotlinx.serialization: `StarosSettingsResponse`, `EqualizerSettingDto`,
  `BluetoothAudioSettingDto`, `BatteryLowPowerModeSettingDto`, `PriorityStreamSettingDto`,
  `ErrorResponse`
- UI: `EqualizerFragment`, `EqualizerViewModel`, `EqualizerUI`, `EqualizerChanged/DragEnd`
- токены: `CompanionAccessTokenProvider`, `GetAuthTokensFeatureFlag`

## Локальные каналы, которые есть в приложении

- mDNS `_staros._tcp.` — единственный локальный сервис (совпадает с `sboom_ha`).
- `ru.sberdevices.sbercastlib` — SberCast (мультирум/cast, `CastRequest`, `CastDirective`,
  `MultiroomInfo`, `SberCastAndroidDiscoveryImpl`) — логика в **нативной .so**
  (`SberCastJniStaticHelper`), и она требует auth-токены (`GetAuthTokensFeatureFlag`).
- `ble/GATT`-операции `BLE_GATT_OP_PAIR_*` — сопряжение по Bluetooth, не управление.

## Модели, которые знает приложение

`StarosType`: SberBoom, SberBoomAura, SberBoomHome, SberBoomMini, SberBoomNova,
SberBoomBasic, SberBoomMicro; строковые id — `sberboom`, `sberboom-mini`, `sberboom-r2`.

## Сверка с живой колонкой

Локальный `GET_STATE` (op=12) на SberBoom Mini содержит `user_settings` ровно такого вида:

```json
{"age_mode": "adult", "enable_child_voice_explicit": false, "multi_profile": false}
```

Ни яркости, ни эквалайзера, ни приоритета источника в локальном состоянии нет.
Это согласуется с фаззингом тел `op=24…64` (все — пустой ack): локального сеттера
для этих настроек в прошивке 1.104.35 нет.

## Вывод

Из оригинального приложения **нельзя** взять локальные команды управления LED,
эквалайзером или мультирумом — их там нет, всё это облачные сетевые API с
аккаунтом. Из APK полезна только номенклатура настроек (что вообще бывает у моделей)
и список моделей; локальная часть полностью покрыта тем, что уже реализовано.
