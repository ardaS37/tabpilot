using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Drawing;
using System.Windows.Forms;

ApplicationConfiguration.Initialize();
Application.Run(new ControllerForm());

sealed class ControllerForm : Form
{
    private readonly Label status = new() { AutoSize = true, Text = "DINLENIYOR", ForeColor = Color.FromArgb(74, 222, 128), Font = new Font("Segoe UI", 10, FontStyle.Bold) };
    private readonly Label connection = new() { AutoSize = true, Text = "Tablet bekleniyor", ForeColor = Color.FromArgb(148, 163, 184), Font = new Font("Segoe UI", 10) };
    private readonly RichTextBox log = new() { ReadOnly = true, BorderStyle = BorderStyle.None, BackColor = Color.FromArgb(15, 23, 42), ForeColor = Color.FromArgb(226, 232, 240), Font = new Font("Cascadia Mono", 9), Dock = DockStyle.Fill };
    private readonly CancellationTokenSource cancellation = new();
    private TcpListener? listener;

    public ControllerForm()
    {
        Text = "Tablet Controller"; ClientSize = new Size(640, 430); MinimumSize = new Size(560, 380);
        BackColor = Color.FromArgb(2, 6, 23); ForeColor = Color.White; StartPosition = FormStartPosition.CenterScreen;
        var header = new Panel { Dock = DockStyle.Top, Height = 112, Padding = new Padding(24, 20, 24, 16), BackColor = Color.FromArgb(15, 23, 42) };
        header.Controls.Add(new Label { Text = "TABLET CONTROLLER", AutoSize = true, Location = new Point(24, 20), ForeColor = Color.White, Font = new Font("Segoe UI", 18, FontStyle.Bold) });
        status.Location = new Point(25, 62); connection.Location = new Point(145, 62); header.Controls.Add(status); header.Controls.Add(connection);
        var gestures = new Label { Dock = DockStyle.Top, Height = 72, Padding = new Padding(24, 14, 10, 6), ForeColor = Color.FromArgb(186, 230, 253), Font = new Font("Segoe UI", 10), Text = "3 parmak ←  Geri     →  İleri     ↑  Görev görünümü     ↓  Masaüstü\n2 parmak ↕ Ters kaydırma     ↔ Zoom" };
        var caption = new Label { Dock = DockStyle.Top, Height = 38, Padding = new Padding(24, 12, 0, 0), ForeColor = Color.FromArgb(148, 163, 184), Text = "CANLI OLAYLAR", Font = new Font("Segoe UI", 9, FontStyle.Bold) };
        Controls.Add(log); Controls.Add(caption); Controls.Add(gestures); Controls.Add(header);
        Shown += async (_, _) => await StartAsync();
        FormClosing += (_, _) => { cancellation.Cancel(); listener?.Stop(); NativeInput.ReleaseAll(); };
    }

    private async Task StartAsync()
    {
        listener = new TcpListener(IPAddress.Loopback, 27183); listener.Start(); AddLog("Alıcı hazır: 127.0.0.1:27183");
        try { while (!cancellation.IsCancellationRequested) _ = HandleClientAsync(await listener.AcceptTcpClientAsync(cancellation.Token), cancellation.Token); }
        catch (OperationCanceledException) { }
        catch (Exception ex) { AddLog($"Alıcı hatası: {ex.Message}"); }
    }
    private async Task HandleClientAsync(TcpClient client, CancellationToken token)
    {
        SetConnection("Tablet bağlı", Color.FromArgb(74, 222, 128)); AddLog("Tablet bağlandı."); var held = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try { using var reader = new StreamReader(client.GetStream(), Encoding.UTF8); while (!token.IsCancellationRequested && await reader.ReadLineAsync(token) is { } line) ProcessCommand(line, held); }
        catch (IOException) { }
        finally { foreach (var key in held) NativeInput.Key(key, false); SetConnection("Tablet bekleniyor", Color.FromArgb(148, 163, 184)); AddLog("Tablet ayrıldı; basılı tuşlar bırakıldı."); client.Dispose(); }
    }
    private void ProcessCommand(string line, HashSet<string> held)
    {
        var p = line.Split(':'); if (p.Length < 2) return;
        if (p[0] == "key" && p.Length == 3) { var down = p[2] == "down"; NativeInput.Key(p[1], down); if (down) held.Add(p[1]); else held.Remove(p[1]); }
        else if (p[0] == "mouse" && p[1] == "move" && p.Length == 4 && int.TryParse(p[2], out var dx) && int.TryParse(p[3], out var dy)) NativeInput.Move(dx, dy);
        else if (p[0] == "mouse" && (p[1] == "left" || p[1] == "right") && p.Length == 3) NativeInput.Button(p[1], p[2] == "down");
        else if (p[0] == "mouse" && p[1] == "scroll" && p.Length == 3 && int.TryParse(p[2], out var delta)) NativeInput.Scroll(delta);
        else if (p[0] == "mouse" && p[1] == "zoom" && p.Length == 3) NativeInput.Zoom(p[2] == "in");
        else if (p[0] == "gesture" && p.Length == 3 && p[1] == "three_swipe") NativeInput.Gesture(p[2]);
        else if (p[0] == "keyboard" && p.Length == 3 && p[1] == "chord" && int.TryParse(p[2], out var chord) && chord is >= 1 and <= 26) NativeInput.Text((char)('A' + chord - 1));
        else if (p[0] == "keyboard" && p.Length == 3 && p[1] == "letter" && p[2].Length == 1) NativeInput.Text(p[2][0]);
        else if (p[0] == "keyboard" && p.Length == 3 && p[1] == "guess" && p[2].Length == 1) PredictiveKeyboard.Type(p[2][0]);
        else if (p[0] == "keyboard" && p.Length == 3 && p[1] == "command") PredictiveKeyboard.Command(p[2]);
        else if (p[0] == "steno" && p.Length == 4 && p[1] == "stroke" && int.TryParse(p[2], out var left) && int.TryParse(p[3], out var right)) NativeInput.Text(TurkishSteno.Translate(left, right));
        AddLog(line);
    }
    private void AddLog(string text) { if (IsDisposed) return; BeginInvoke(() => { log.AppendText($"{DateTime.Now:HH:mm:ss}  {text}\n"); log.SelectionStart = log.TextLength; log.ScrollToCaret(); }); }
    private void SetConnection(string text, Color color) { if (!IsDisposed) BeginInvoke(() => { connection.Text = text; connection.ForeColor = color; }); }
}

static class NativeInput
{
    private const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1, KEYEVENTF_KEYUP = 0x0002, KEYEVENTF_SCANCODE = 0x0008;
    private const uint MOUSEEVENTF_MOVE = 1, MOUSEEVENTF_LEFTDOWN = 2, MOUSEEVENTF_LEFTUP = 4, MOUSEEVENTF_RIGHTDOWN = 8, MOUSEEVENTF_RIGHTUP = 0x10, MOUSEEVENTF_WHEEL = 0x800;
    private static readonly Dictionary<string, ushort> Keys = new(StringComparer.OrdinalIgnoreCase) { ["W"]=0x11,["A"]=0x1E,["S"]=0x1F,["D"]=0x20,["E"]=0x12,["F"]=0x21,["Q"]=0x10,["R"]=0x13,["SPACE"]=0x39,["SHIFT"]=0x2A,["CTRL"]=0x1D,["1"]=0x02,["2"]=0x03,["3"]=0x04 };
    public static void Key(string name, bool down) { if (Keys.TryGetValue(name, out var scan)) Send(new INPUT { type=INPUT_KEYBOARD, U=new InputUnion { ki=new KEYBDINPUT { wScan=scan, dwFlags=KEYEVENTF_SCANCODE | (down ? 0u : KEYEVENTF_KEYUP) } } }); }
    public static void Move(int x,int y)=>Send(new INPUT{type=INPUT_MOUSE,U=new InputUnion{mi=new MOUSEINPUT{dx=x,dy=y,dwFlags=MOUSEEVENTF_MOVE}}});
    public static void Button(string button,bool down)=>Send(new INPUT{type=INPUT_MOUSE,U=new InputUnion{mi=new MOUSEINPUT{dwFlags=button=="left"?(down?MOUSEEVENTF_LEFTDOWN:MOUSEEVENTF_LEFTUP):(down?MOUSEEVENTF_RIGHTDOWN:MOUSEEVENTF_RIGHTUP)}}});
    public static void Scroll(int delta)=>Send(new INPUT{type=INPUT_MOUSE,U=new InputUnion{mi=new MOUSEINPUT{mouseData=unchecked((uint)delta),dwFlags=MOUSEEVENTF_WHEEL}}});
    public static void Zoom(bool zoomIn) => Shortcut(0x11, zoomIn ? (ushort)0xBB : (ushort)0xBD);
    public static void Backspace() { VirtualKey(0x08, true); VirtualKey(0x08, false); }
    public static void Enter() { VirtualKey(0x0D, true); VirtualKey(0x0D, false); }
    public static void Gesture(string direction) { switch(direction) { case "left": Shortcut(0x12,0x25); break; case "right": Shortcut(0x12,0x27); break; case "up": Shortcut(0x5B,0x09); break; case "down": Shortcut(0x5B,0x44); break; } }
    private static void Shortcut(params ushort[] keys) { foreach(var key in keys) VirtualKey(key,true); for(var i=keys.Length-1;i>=0;i--) VirtualKey(keys[i],false); }
    private static void VirtualKey(ushort key,bool down)=>Send(new INPUT{type=INPUT_KEYBOARD,U=new InputUnion{ki=new KEYBDINPUT{wVk=key,dwFlags=down?0:KEYEVENTF_KEYUP}}});
    public static void Text(char character) => Text(character.ToString());
    public static void Text(string text) { foreach (var character in text) { Send(new INPUT { type=INPUT_KEYBOARD,U=new InputUnion{ki=new KEYBDINPUT{wScan=character,dwFlags=0x0004}}}); Send(new INPUT { type=INPUT_KEYBOARD,U=new InputUnion{ki=new KEYBDINPUT{wScan=character,dwFlags=0x0004|KEYEVENTF_KEYUP}}}); } }
    public static void ReleaseAll() { }
    private static void Send(INPUT input)=>SendInput(1,new[]{input},Marshal.SizeOf<INPUT>());
    [DllImport("user32.dll",SetLastError=true)] private static extern uint SendInput(uint nInputs,INPUT[] pInputs,int cbSize);
    [StructLayout(LayoutKind.Sequential)] private struct INPUT { public uint type; public InputUnion U; }
    [StructLayout(LayoutKind.Explicit)] private struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)] private struct MOUSEINPUT { public int dx,dy; public uint mouseData,dwFlags,time; public nint dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] private struct KEYBDINPUT { public ushort wVk,wScan; public uint dwFlags,time; public nint dwExtraInfo; }
}

static class TurkishSteno
{
    // Left strokes are consonants; right strokes are vowels. Both sides use five
    // top-to-bottom positions, so one chord makes a Turkish phonetic syllable.
    private static readonly Dictionary<int, string> Consonants = new()
    {
        [1] = "k", [2] = "t", [4] = "s", [8] = "m", [16] = "l",
        [3] = "g", [6] = "d", [12] = "n", [24] = "r", [17] = "p",
        [10] = "b", [20] = "y", [9] = "ç", [18] = "f", [5] = "h",
        [14] = "ş", [28] = "z", [19] = "v", [21] = "c", [26] = "ğ"
    };
    private static readonly Dictionary<int, string> Vowels = new()
    {
        [1] = "a", [2] = "e", [4] = "ı", [8] = "o", [16] = "u",
        [6] = "i", [12] = "ö", [24] = "ü"
    };
    public static string Translate(int left, int right)
    {
        if (left == 31 && right == 31) return " ";
        Consonants.TryGetValue(left, out var consonant); Vowels.TryGetValue(right, out var vowel);
        return (consonant ?? "") + (vowel ?? "");
    }
}

static class PredictiveKeyboard
{
    private static readonly StringBuilder CurrentWord = new();
    private static readonly string[] Dictionary = ["merhaba", "nasıl", "neden", "nerede", "teşekkür", "lütfen", "tamam", "evet", "hayır", "bugün", "yarın", "bilgisayar", "tablet", "klavye", "mouse", "uygulama", "program", "türkçe", "deneme", "güzel", "çok", "şimdi", "bunu", "böyle", "çalışıyor", "çalışmıyor", "yardım", "isterim", "yazmak", "selam"];
    public static void Type(char character) { NativeInput.Text(character); CurrentWord.Append(character); }
    public static void Command(string command)
    {
        if (command == "backspace") { NativeInput.Backspace(); if (CurrentWord.Length > 0) CurrentWord.Length--; return; }
        if (command == "enter") { Commit(); NativeInput.Enter(); return; }
        if (command == "space") { Commit(); NativeInput.Text(" "); }
    }
    private static void Commit()
    {
        var typed = CurrentWord.ToString(); CurrentWord.Clear(); if (typed.Length < 3) return;
        var best = Dictionary.OrderBy(word => Distance(typed, word)).First();
        if (Distance(typed, best) <= Math.Max(1, typed.Length / 3) && !string.Equals(typed, best, StringComparison.Ordinal))
        {
            for (var i = 0; i < typed.Length; i++) NativeInput.Backspace();
            NativeInput.Text(best);
        }
    }
    private static int Distance(string a, string b)
    {
        var d = new int[a.Length + 1, b.Length + 1]; for (var i = 0; i <= a.Length; i++) d[i, 0] = i; for (var j = 0; j <= b.Length; j++) d[0, j] = j;
        for (var i = 1; i <= a.Length; i++) for (var j = 1; j <= b.Length; j++) d[i, j] = Math.Min(Math.Min(d[i - 1, j] + 1, d[i, j - 1] + 1), d[i - 1, j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1));
        return d[a.Length, b.Length];
    }
}
