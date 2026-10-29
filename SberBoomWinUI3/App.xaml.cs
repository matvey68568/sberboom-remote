using Microsoft.UI.Xaml;

namespace SberBoom.WinUI;

public partial class App : Application
{
    private Window? _window;

    public App()
    {
        InitializeComponent();
        UnhandledException += (_, e) =>
        {
            Program.Log("App.UnhandledException: " + e.Exception);
            e.Handled = true;
        };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Program.Log("OnLaunched");
        _window = new MainWindow();
        _window.Activate();
        Program.Log("window activated");
    }
}
