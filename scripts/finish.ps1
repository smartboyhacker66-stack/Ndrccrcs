param([int]$Success = 0)
$ErrorActionPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

if ($Success -eq 1) {
    $roman = 'Bhai Cloudwa, kaam kar diya maine, Cloudwa ne.'
    $hindi = 'भाई क्लाउडवा काम कर दिया मैंने क्लाउडवा ने'
    Write-Host ''
    Write-Host $roman -ForegroundColor Green
    Write-Host $hindi -ForegroundColor Green
    $say = $roman
} else {
    $say = 'The build did not finish successfully. The error is shown above.'
    Write-Host ''
    Write-Host $say -ForegroundColor Yellow
}

try {
    Add-Type -AssemblyName System.Speech
    $voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $voice.Speak($say)
} catch {}
