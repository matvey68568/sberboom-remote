namespace SberBoom.WinUI.Protocol;

/// <summary>Константы протокола SBoom (LAN, WSS :20000). Порт protocol/Proto.kt.</summary>
public static class Proto
{
    public const int DefaultPort = 20000;
    public const string UserAgent = "WebSocket++/0.8.2";

    // Опкоды (тег поля в request_data).
    public const int OpPinConnect = 4;
    public const int OpGetMetaData = 10;
    public const int OpGetState = 12;
    public const int OpFindRemote = 13;
    public const int OpSetVolume = 14;
    public const int OpSetTrackPos = 15;
    public const int OpMediaCommand = 16;
    public const int OpGetPlayingQueue = 17;
    public const int OpKeepAlive = 18;
    public const int OpEcho = 8;
    public const int OpGetPairedBt = 19;
    public const int OpBtDeviceCommand = 20;
    public const int OpGetScannedBt = 21;
    public const int OpBtDiscoverable = 22;
    public const int OpSetPlaybackSpeed = 23;

    // Конверт ответа: поле 3 = статус. 1 = ok, 4 = токен не принят.
    public const int EnvelopeFieldStatus = 3;
    public const int StatusOk = 1;
    public const int StatusUnauthorized = 4;

    // Медиа-команды (поле action внутри op=16).
    public const int MediaMute = 0;
    public const int MediaUnmute = 1;
    public const int MediaNext = 2;
    public const int MediaPrev = 3;
    public const int MediaPlay = 4;
    public const int MediaPause = 5;
    public const int MediaLike = 6;
    public const int MediaRemoveLike = 7;
    public const int MediaShuffleOn = 9;
    public const int MediaShuffleOff = 10;
    public const int MediaRepeatNone = 11;
    public const int MediaRepeatPlaylist = 12;
    public const int MediaRepeatTrack = 13;
    public const int MediaDislike = 14;

    public const int TokenTypePin = 1;

    // Статусы pair-handshake.
    public const int PairWaiting = 1;
    public const int PairAuthorized = 2;
    public const int PairBusy = 3;
    public const int PairDisabled = 5;
    public const int PairConfirmOk = 1;
    public const int PairConfirmRejected = 2;

    public const float SpeedMin = 0.5f;
    public const float SpeedMax = 2.0f;
    public const int VolumeMax = 100;

    public const int PairTimeoutMs = 120_000;

    public const string ZvukImageCdn = "https://cdn-image.zvuk.com/pic";
    public const string CoverSize = "600x600";
}
