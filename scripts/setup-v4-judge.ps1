param(
    [string]$VenvPath = ".tools/v4-judge-venv"
)

$ErrorActionPreference = "Stop"
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$venv = if ([IO.Path]::IsPathRooted($VenvPath)) {
    [IO.Path]::GetFullPath($VenvPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $VenvPath))
}
$python = Join-Path $venv "Scripts/python.exe"
$requirements = Join-Path $root "requirements-v4-judge.txt"

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    python -m venv $venv
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create the V4 judge virtual environment."
    }
}

& $python -m pip install --disable-pip-version-check --no-cache-dir -r $requirements
if ($LASTEXITCODE -ne 0) {
    throw "Failed to install the V4 judge dependencies."
}

& $python -c "from pysat.solvers import Solver; import pysat; solver = Solver(name='cadical195'); solver.add_clause([1]); assert solver.solve(); print('V4 judge ready:', pysat.__version__, 'cadical195')"
if ($LASTEXITCODE -ne 0) {
    throw "The installed V4 judge dependencies failed verification."
}

Write-Output "CRYPTOBENCH_V4_PYTHON=$python"
