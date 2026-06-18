param(
    [string]$Version = "3.9.9",
    [string]$Dest = ".maven",
    [string]$Sha256 = ""
)
$ErrorActionPreference = "Stop"

$url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$Version/apache-maven-$Version-bin.zip"
$zip = Join-Path $env:TEMP "apache-maven-$Version-bin.zip"

Write-Host "[get-maven] downloading $url"
Invoke-WebRequest -Uri $url -OutFile $zip

if ($Sha256 -ne "") {
    $actual = (Get-FileHash -Algorithm SHA256 -Path $zip).Hash.ToLower()
    if ($actual -ne $Sha256.ToLower()) {
        Remove-Item $zip -Force
        throw "[get-maven] checksum mismatch: expected $Sha256 but got $actual"
    }
    Write-Host "[get-maven] checksum verified"
}

New-Item -ItemType Directory -Force -Path $Dest | Out-Null
Write-Host "[get-maven] extracting to $Dest"
Expand-Archive -Path $zip -DestinationPath $Dest -Force
Remove-Item $zip -Force

Write-Host "[get-maven] done: $Dest\apache-maven-$Version"
