using System;
using System.IO;
using System.Threading;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using WinRT;

namespace SberBoom.WinUI;

/// <summary>
/// Точка входа. Своя, а не сгенерированная XAML-компилятором: нужен перехват
/// исключений и запись в лог, иначе WinUI-приложение без консоли падает молча.
/// </summary>
public static class Program
{
    private static readonly string LogPath =
        Path.Combine(Path.GetTempPath(), "sberboom-winui.log");

    internal static void Log(string message)
    {
        try
        {
            File.AppendAllText(LogPath, $"{DateTime.Now:HH:mm:ss.fff} {message}\n");
        }
        catch
        {
            // лог — не повод падать
        }
    }

    [STAThread]
    private static void Main(string[] args)
    {
        try
        {
            File.WriteAllText(LogPath, $"{DateTime.Now:HH:mm:ss.fff} start\n");
            Log($"runtime={Environment.Version} os={Environment.OSVersion}");
            AppDomain.CurrentDomain.UnhandledException += (_, e) => Log("Unhandled: " + e.ExceptionObject);

            try
            {
                var ok = Microsoft.Windows.ApplicationModel.DynamicDependency.Bootstrap.TryInitialize(
                    0x00010007, null, default, out var hr);
                Log($"bootstrap: ok={ok} hr=0x{hr:X8}");
            }
            catch (Exception ex)
            {
                Log("bootstrap threw: " + ex);
            }

            ComWrappersSupport.InitializeComWrappers();
            Log("com wrappers ok");

            Application.Start(p =>
            {
                var queue = DispatcherQueue.GetForCurrentThread();
                SynchronizationContext.SetSynchronizationContext(
                    new DispatcherQueueSynchronizationContext(queue));
                Log("dispatcher ok");
                new App();
                Log("app constructed");
            });

            Log("Application.Start returned");
        }
        catch (Exception ex)
        {
            Log("FATAL " + ex);
            throw;
        }
    }
}
