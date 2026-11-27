# Writes .vscode/settings.json for THIS machine (JDK 8 path, Metals Java). Called by setup-local.cmd.
# Existing settings.json is left alone.
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root '.vscode\settings.json'
if (Test-Path $target) { Write-Host 'settings.json already exists, left unchanged.'; exit 0 }

$jdk = ($env:JAVA_HOME).TrimEnd('\').Replace('\', '/')
$settings = [ordered]@{
  'java.configuration.updateBuildConfiguration' = 'automatic'
  'debug.allowBreakpointsEverywhere'            = $true
  'java.configuration.runtimes'                 = @(@{ name = 'JavaSE-1.8'; path = $jdk; default = $true })
  'files.watcherExclude'                        = @{ '**/target/**' = $true }
}

# Metals needs Java 17+; reuse the runtime bundled with the Red Hat Java extension if it is installed.
$jre = Get-ChildItem "$env:USERPROFILE\.vscode\extensions\redhat.java-*\jre\*" -Directory -ErrorAction SilentlyContinue |
       Sort-Object Name -Descending | Select-Object -First 1
if ($jre) { $settings['metals.javaHome'] = $jre.FullName.Replace('\', '/') }
else { Write-Host 'Red Hat Java extension not found yet: install it, then set metals.javaHome (see README).' }

New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
$settings | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $target
Write-Host "Wrote $target"
