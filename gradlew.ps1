param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root = $PSScriptRoot
$GradleVersion = '9.5.1'
$ToolsDir = Join-Path $Root '.gradle-local'
$DownloadsDir = Join-Path $ToolsDir 'downloads'
$GradleHome = Join-Path $ToolsDir ("gradle-{0}" -f $GradleVersion)
$GradleExe = Join-Path $GradleHome 'bin\gradle.bat'
$ZipFile = Join-Path $DownloadsDir ("gradle-{0}-bin.zip" -f $GradleVersion)
$ShaFile = "$ZipFile.sha256"
$DistributionUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
$ChecksumUrl = "$DistributionUrl.sha256"

New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
New-Item -ItemType Directory -Force -Path $DownloadsDir | Out-Null

if (-not (Test-Path $GradleExe)) {
    Write-Host "[minecraft-mod] Downloading Gradle $GradleVersion..."

    $PartFile = "$ZipFile.part"
    Remove-Item -Force -ErrorAction SilentlyContinue $PartFile
    Invoke-WebRequest -UseBasicParsing -Uri $DistributionUrl -OutFile $PartFile
    Move-Item -Force $PartFile $ZipFile

    Write-Host '[minecraft-mod] Verifying Gradle SHA-256...'
    Invoke-WebRequest -UseBasicParsing -Uri $ChecksumUrl -OutFile $ShaFile
    $ExpectedHash = ((Get-Content $ShaFile -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
    $ActualHash = (Get-FileHash -Algorithm SHA256 $ZipFile).Hash.ToLowerInvariant()
    if ($ActualHash -ne $ExpectedHash) {
        Remove-Item -Force $ZipFile
        throw "Gradle checksum mismatch. Expected $ExpectedHash, got $ActualHash"
    }

    Write-Host '[minecraft-mod] Extracting Gradle into project directory...'
    if (Test-Path $GradleHome) {
        Remove-Item -Recurse -Force $GradleHome
    }
    Expand-Archive -Path $ZipFile -DestinationPath $ToolsDir -Force
}

if (-not (Test-Path $GradleExe)) {
    throw "Gradle executable was not found after extraction: $GradleExe"
}

# Keep Gradle caches and auto-provisioned Java toolchains inside this project.
$env:GRADLE_USER_HOME = Join-Path $Root '.gradle-user-home'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null

Write-Host "[minecraft-mod] Gradle: $GradleVersion"
Write-Host "[minecraft-mod] GRADLE_USER_HOME: $env:GRADLE_USER_HOME"
Write-Host '[minecraft-mod] Java 25 will be auto-provisioned by Gradle if it is not installed.'

& $GradleExe --project-dir $Root @GradleArgs
exit $LASTEXITCODE
