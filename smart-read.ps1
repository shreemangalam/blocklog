param(
    [Parameter(Mandatory=$true)]
    [string]$Path
)

if (!(Test-Path $Path)) {
    Write-Error "File not found: $Path"
    exit 1
}

$lines = Get-Content $Path
$totalLines = $lines.Count

# If the file is small enough, just output it directly
if ($totalLines -lt 250) {
    $lines
    exit 0
}

Write-Host "=== FILE SKELETON: $Path ($totalLines lines compressed) ==="

$extension = [System.IO.Path]::GetExtension($Path).ToLower()

if ($extension -match '\.(java|ts|tsx|js)$') {
    # Extract package, imports, class definitions, and method signatures
    $skeleton = $lines | Where-Object {
        $_ -match '^(package|import)\b' -or
        $_ -match '\b(class|interface|enum|record)\s+\w+' -or
        $_ -match '^\s*(public|private|protected|export)\b.*(\{|\;)\s*$' -or
        $_ -match '^\s*(function|const|let)\b.*(=|\{)'
    } 
    
    $skeleton | ForEach-Object { $_.Trim() }
} 
else {
    # For logs or unknown large files, grab the head and tail
    $lines | Select-Object -First 50
    Write-Host "... [CONTENT TRUNCATED TO SAVE TOKENS] ..."
    $lines | Select-Object -Last 50
}