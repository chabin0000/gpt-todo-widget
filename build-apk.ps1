param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$BuildTools,
    [string]$WorkDir = (Join-Path $PSScriptRoot ('build\manual-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))),
    [string]$SigningKey = (Join-Path $PSScriptRoot 'build\local-debug.jks'),
    [string]$RuntimeDirectory = (Join-Path $PSScriptRoot 'app\src\main\jniLibs'),
    [string]$OutputFile = (Join-Path $PSScriptRoot '..\GptTodoWidget.apk')
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$main = Join-Path $projectRoot 'app\src\main'
$WorkDir = [IO.Path]::GetFullPath($WorkDir)
$OutputFile = [IO.Path]::GetFullPath($OutputFile)
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $OutputFile -Parent) | Out-Null
$generated = Join-Path $WorkDir 'generated'
$classes = Join-Path $WorkDir 'classes'
$dex = Join-Path $WorkDir 'dex'
New-Item -ItemType Directory -Force -Path $generated,$classes,$dex | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Run-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Command failed: ' + $Program + ' (exit ' + $LASTEXITCODE + ')') }
}
$java = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$aapt = Join-Path $BuildTools 'aapt2.exe'
$env:JAVA_HOME = $JavaHome
# Gradle supplies the namespace; standalone AAPT2 needs the package on its generated manifest.
$manifest = Get-Content -Raw -LiteralPath (Join-Path $main 'AndroidManifest.xml')
$manifest = $manifest.Replace('<manifest xmlns:', '<manifest package="com.local.gpttodo" xmlns:')
$manifestPath = Join-Path $WorkDir 'AndroidManifest.xml'
[IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
$resources = Join-Path $WorkDir 'resources.zip'
$unsigned = Join-Path $WorkDir 'unsigned.apk'
Run-Checked $aapt @('compile','--dir',(Join-Path $main 'res'),'-o',$resources)
Run-Checked $aapt @('link','-o',$unsigned,'-I',$AndroidJar,'--manifest',$manifestPath,'--java',$generated,
    '--min-sdk-version','31','--target-sdk-version','36','--version-code','3','--version-name','2.0-public-preview',
    '-A',(Join-Path $main 'assets'),$resources)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $main 'java'),$generated -Filter '*.java' -Recurse | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
$argsFile = Join-Path $WorkDir 'java-sources.txt'
[IO.File]::WriteAllLines($argsFile, $sources, [Text.UTF8Encoding]::new($false))
Run-Checked $javac @('-encoding','UTF-8','-source','17','-target','17','-classpath',$AndroidJar,'-d',$classes,('@' + $argsFile))
$classesJar = Join-Path $WorkDir 'classes.jar'
Run-Checked $jar @('--create','--file',$classesJar,'-C',$classes,'.')
Run-Checked $java @('-cp',(Join-Path $BuildTools 'lib\d8.jar'),'com.android.tools.r8.D8','--lib',$AndroidJar,'--min-api','31','--output',$dex,$classesJar)
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    # AAPT2 on Windows may put backslashes in nested asset names. APK paths use '/'.
    foreach($entry in @($archive.Entries | Where-Object { $_.FullName.Contains('\') })) {
        $normalized=$archive.CreateEntry($entry.FullName.Replace('\','/'),[IO.Compression.CompressionLevel]::Optimal)
        $sourceStream=$entry.Open();$targetStream=$normalized.Open()
        try{$sourceStream.CopyTo($targetStream)}finally{$sourceStream.Dispose();$targetStream.Dispose()}
        $entry.Delete()
    }
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $dex 'classes.dex'), 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    if(-not(Test-Path -LiteralPath $RuntimeDirectory)){throw 'Native runtime missing. Run tools/fetch-runtime.cjs or pass -RuntimeDirectory.'}
    foreach($library in (Get-ChildItem -LiteralPath $RuntimeDirectory -Filter '*.so' -Recurse)){
        $abi=$library.Directory.Name
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$library.FullName,('lib/'+$abi+'/'+$library.Name),[IO.Compression.CompressionLevel]::Optimal)|Out-Null
    }
} finally { $archive.Dispose() }
$aligned = Join-Path $WorkDir 'aligned.apk'
Run-Checked (Join-Path $BuildTools 'zipalign.exe') @('-f','-p','4',$unsigned,$aligned)
$key = [IO.Path]::GetFullPath($SigningKey)
New-Item -ItemType Directory -Force -Path (Split-Path $key -Parent) | Out-Null
if (-not (Test-Path -LiteralPath $key)) {
    Run-Checked (Join-Path $JavaHome 'bin\keytool.exe') @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android',
        '-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Local GPT Todo,O=Local,C=KR')
}
Run-Checked $java @('-jar',(Join-Path $BuildTools 'lib\apksigner.jar'),'sign','--ks',$key,'--ks-key-alias','androiddebugkey',
    '--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',$OutputFile,$aligned)
Run-Checked $java @('-jar',(Join-Path $BuildTools 'lib\apksigner.jar'),'verify','--verbose','--print-certs',$OutputFile)
Run-Checked (Join-Path $BuildTools 'zipalign.exe') @('-c','-p','4',$OutputFile)
Write-Output ('APK: ' + $OutputFile)
Get-FileHash -LiteralPath $OutputFile -Algorithm SHA256
