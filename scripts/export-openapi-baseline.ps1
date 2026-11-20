param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$game = Join-Path $root 'delta-game'
$contracts = Join-Path $root 'contracts'
$baseline = Join-Path $contracts 'openapi.baseline.json'

if (-not $JavaHome) {
    throw 'JAVA_HOME is not set. Pass -JavaHome or export JAVA_HOME.'
}
if (-not $Maven) {
    $wrapper = Join-Path $game 'mvnw.cmd'
    if (Test-Path $wrapper) {
        $Maven = $wrapper
    } else {
        $Maven = 'mvn'
    }
}

$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
New-Item -ItemType Directory -Force -Path $contracts | Out-Null

& $Maven -f (Join-Path $game 'pom.xml') -pl delta-admin -am test -q '-Dtest=OpenApiBaselineTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dopenapi.baseline.write=true' "-Dopenapi.baseline.path=$baseline"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python (Join-Path $root 'scripts/check_openapi_baseline.py')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python (Join-Path $root 'scripts/generate_openapi_types.py')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python (Join-Path $root 'scripts/check_openapi_types.py')
exit $LASTEXITCODE
