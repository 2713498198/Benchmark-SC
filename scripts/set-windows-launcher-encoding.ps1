[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$File)

$ErrorActionPreference = 'Stop'
$File = (Resolve-Path -LiteralPath $File).Path
# JDK 21's native launcher converts paths through CP_ACP before loading Java.
# Opt this executable into UTF-8 (Windows 10 1903+) without changing system locale.
# Preserve jpackage's DPI, compatibility and asInvoker declarations. Do this
# before Authenticode signing and before computing the payload manifest.
if (-not ('CryptoBench.LauncherManifest' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace CryptoBench {
    public static class LauncherManifest {
        private delegate bool LanguageCallback(IntPtr module, IntPtr type, IntPtr name, ushort language, IntPtr parameter);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryExW(string file, IntPtr reserved, uint flags);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool FreeLibrary(IntPtr module);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool EnumResourceLanguagesW(IntPtr module, IntPtr type, IntPtr name, LanguageCallback callback, IntPtr parameter);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr FindResourceExW(IntPtr module, IntPtr type, IntPtr name, ushort language);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint SizeofResource(IntPtr module, IntPtr resource);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr LockResource(IntPtr resource);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr BeginUpdateResourceW(string file, bool deleteExisting);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool UpdateResourceW(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool EndUpdateResourceW(IntPtr update, bool discard);

        public static Dictionary<ushort, string> Read(string file) {
            IntPtr module = LoadLibraryExW(file, IntPtr.Zero, 2); // data only: never execute the file
            if (module == IntPtr.Zero) throw new Win32Exception();
            try {
                var languages = new List<ushort>();
                LanguageCallback callback = (m, t, n, l, p) => { languages.Add(l); return true; };
                if (!EnumResourceLanguagesW(module, (IntPtr)24, (IntPtr)1, callback, IntPtr.Zero)) throw new Win32Exception();
                var result = new Dictionary<ushort, string>();
                foreach (ushort language in languages) {
                    IntPtr resource = FindResourceExW(module, (IntPtr)24, (IntPtr)1, language);
                    if (resource == IntPtr.Zero) throw new Win32Exception();
                    uint size = SizeofResource(module, resource);
                    IntPtr data = LockResource(LoadResource(module, resource));
                    if (size == 0 || data == IntPtr.Zero) throw new Win32Exception();
                    byte[] bytes = new byte[size];
                    Marshal.Copy(data, bytes, 0, bytes.Length);
                    result.Add(language, Encoding.UTF8.GetString(bytes).TrimEnd('\0'));
                }
                return result;
            } finally { FreeLibrary(module); }
        }

        public static void Write(string file, Dictionary<ushort, string> manifests) {
            IntPtr update = BeginUpdateResourceW(file, false);
            if (update == IntPtr.Zero) throw new Win32Exception();
            try {
                foreach (var entry in manifests) {
                    byte[] data = Encoding.UTF8.GetBytes(entry.Value);
                    if (!UpdateResourceW(update, (IntPtr)24, (IntPtr)1, entry.Key, data, (uint)data.Length)) throw new Win32Exception();
                }
                bool saved = EndUpdateResourceW(update, false);
                update = IntPtr.Zero;
                if (!saved) throw new Win32Exception();
            } finally { if (update != IntPtr.Zero) EndUpdateResourceW(update, true); }
        }
    }
}
'@
}
$manifests = [CryptoBench.LauncherManifest]::Read($File)
foreach ($language in @($manifests.Keys)) {
    $xml = New-Object System.Xml.XmlDocument
    $xml.LoadXml($manifests[$language])
    $namespaces = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
    $namespaces.AddNamespace('v3', 'urn:schemas-microsoft-com:asm.v3')
    $namespaces.AddNamespace('ws', 'http://schemas.microsoft.com/SMI/2019/WindowsSettings')
    $settings = $xml.SelectSingleNode('//v3:application/v3:windowsSettings', $namespaces)
    if (-not $settings) { throw 'Unrecognized jpackage manifest: windowsSettings is missing.' }
    $encoding = $settings.SelectSingleNode('ws:activeCodePage', $namespaces)
    if (-not $encoding) {
        $encoding = $xml.CreateElement('activeCodePage', 'http://schemas.microsoft.com/SMI/2019/WindowsSettings')
        $settings.AppendChild($encoding) | Out-Null
    }
    $encoding.InnerText = 'UTF-8'
    $manifests[$language] = $xml.OuterXml
}
(Get-Item -LiteralPath $File).IsReadOnly = $false
[CryptoBench.LauncherManifest]::Write($File, $manifests)
$actual = [CryptoBench.LauncherManifest]::Read($File)
foreach ($language in $manifests.Keys) {
    if ($actual[$language] -ne $manifests[$language]) { throw 'Launcher manifest verification failed.' }
}
Write-Host 'Windows launcher uses a UTF-8 process code page.'
