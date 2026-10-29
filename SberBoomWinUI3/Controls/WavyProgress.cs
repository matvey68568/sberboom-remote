using System;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.UI;

namespace SberBoom.WinUI.Controls;

/// <summary>
/// Волнистый индикатор прогресса (Material 3 Expressive).
///
/// Порт ui/WavyProgress из общего Kotlin-кода: по прошедшей части бежит
/// синусоида, непрошедшая часть — ровная линия; на паузе волна схлопывается.
/// Тап и перетаскивание — перемотка (событие Seek).
/// </summary>
public sealed class WavyProgress : Grid
{
    private readonly Line _activeBase = new() { StrokeThickness = 7, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };
    private readonly Line _inactive = new() { StrokeThickness = 7, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };
    private readonly Polyline _wave = new() { StrokeThickness = 7, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };
    private readonly Ellipse _thumb = new() { Width = 14, Height = 14 };
    private readonly Ellipse _thumbDot = new() { Width = 6, Height = 6, Fill = new SolidColorBrush(Colors.White) };

    private readonly DispatcherTimer _timer;
    private double _phase;
    private double _progress;
    private bool _animated;
    private bool _dragging;
    private bool _seekable = true;

    public event EventHandler<double>? Seek;
    public event EventHandler<double>? SeekFinished;

    public static readonly DependencyProperty ActiveColorProperty = DependencyProperty.Register(
        nameof(ActiveColor), typeof(Color), typeof(WavyProgress),
        new PropertyMetadata(Color.FromArgb(255, 59, 224, 137), (d, _) => ((WavyProgress)d).ApplyColors()));

    public static readonly DependencyProperty InactiveColorProperty = DependencyProperty.Register(
        nameof(InactiveColor), typeof(Color), typeof(WavyProgress),
        new PropertyMetadata(Color.FromArgb(255, 30, 36, 45), (d, _) => ((WavyProgress)d).ApplyColors()));

    public Color ActiveColor
    {
        get => (Color)GetValue(ActiveColorProperty);
        set => SetValue(ActiveColorProperty, value);
    }

    public Color InactiveColor
    {
        get => (Color)GetValue(InactiveColorProperty);
        set => SetValue(InactiveColorProperty, value);
    }

    public bool Seekable
    {
        get => _seekable;
        set => _seekable = value;
    }

    /// <summary>Прошедшая часть, 0..1.</summary>
    public double Progress
    {
        get => _progress;
        set
        {
            _progress = Math.Clamp(value, 0, 1);
            if (!_dragging) Redraw();
        }
    }

    /// <summary>Волна бежит только во время воспроизведения.</summary>
    public bool Animated
    {
        get => _animated;
        set
        {
            if (_animated == value) return;
            _animated = value;
            if (_animated) _timer.Start();
            else
            {
                _timer.Stop();
                _phase = 0;
                Redraw();
            }
        }
    }

    public WavyProgress()
    {
        Height = 36;
        Margin = new Thickness(0);

        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(33) };
        _timer.Tick += (_, _) =>
        {
            _phase += 0.14;
            if (_phase > Math.PI * 2) _phase -= Math.PI * 2;
            Redraw();
        };

        Children.Add(_activeBase);
        Children.Add(_inactive);
        Children.Add(_wave);
        Children.Add(_thumb);
        Children.Add(_thumbDot);

        PointerPressed += OnPointerPressed;
        PointerMoved += OnPointerMoved;
        PointerReleased += OnPointerReleased;
        PointerCaptureLost += (_, _) => _dragging = false;
        PointerCanceled += (_, _) => _dragging = false;

        SizeChanged += (_, _) => Redraw();
        Loaded += (_, _) => ApplyColors();
    }

    private void ApplyColors()
    {
        _activeBase.Stroke = new SolidColorBrush(ActiveColor);
        _activeBase.Opacity = 0.35;
        _inactive.Stroke = new SolidColorBrush(InactiveColor);
        _wave.Stroke = new SolidColorBrush(ActiveColor);
        _thumb.Fill = new SolidColorBrush(ActiveColor);
        Redraw();
    }

    private void OnPointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (!_seekable) return;
        _dragging = true;
        CapturePointer(e.Pointer);
        ApplyFromPoint(e.GetCurrentPoint(this).Position.X, finished: false);
    }

    private void OnPointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (!_dragging) return;
        ApplyFromPoint(e.GetCurrentPoint(this).Position.X, finished: false);
    }

    private void OnPointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (!_dragging) return;
        _dragging = false;
        ReleasePointerCapture(e.Pointer);
        SeekFinished?.Invoke(this, _progress);
    }

    private void ApplyFromPoint(double x, bool finished)
    {
        var width = ActualWidth;
        if (width <= 0) return;
        _progress = Math.Clamp(x / width, 0, 1);
        Redraw();
        Seek?.Invoke(this, _progress);
    }

    private void Redraw()
    {
        var width = ActualWidth;
        var height = ActualHeight;
        if (width <= 0 || height <= 0) return;

        var cy = height / 2.0;
        var thickness = 7.0;
        var activeWidth = width * _progress;
        var thumbRadius = _dragging ? 9.5 : 7.0;
        var thumbX = Math.Clamp(activeWidth, thumbRadius, Math.Max(thumbRadius, width - thumbRadius));

        _activeBase.X1 = 0;
        _activeBase.Y1 = cy;
        _activeBase.X2 = activeWidth;
        _activeBase.Y2 = cy;

        _inactive.X1 = activeWidth + thickness / 2;
        _inactive.Y1 = cy;
        _inactive.X2 = width - thickness / 2;
        _inactive.Y2 = cy;

        _wave.Points.Clear();
        if (activeWidth > 1)
        {
            var amplitude = _animated ? 5.5 : 0;
            var waveLength = 26.0;
            for (var x = 0.0; x <= activeWidth; x += 2)
            {
                // у конца волна затухает, чтобы не обрывалась
                var edge = Math.Min(activeWidth - x, waveLength / 2) / (waveLength / 2);
                var y = cy + Math.Sin(x / waveLength * Math.PI * 2 + _phase) * amplitude * edge;
                _wave.Points.Add(new Windows.Foundation.Point(x, y));
            }
        }

        _thumb.Width = thumbRadius * 2;
        _thumb.Height = thumbRadius * 2;
        _thumb.SetValue(Canvas.LeftProperty, thumbX - thumbRadius);
        _thumb.SetValue(Canvas.TopProperty, cy - thumbRadius);

        _thumbDot.Width = thumbRadius * 0.8;
        _thumbDot.Height = thumbRadius * 0.8;
        _thumbDot.SetValue(Canvas.LeftProperty, thumbX - thumbRadius * 0.4);
        _thumbDot.SetValue(Canvas.TopProperty, cy - thumbRadius * 0.4);
    }
}
