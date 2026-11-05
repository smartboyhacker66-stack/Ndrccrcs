param(
    [Parameter(Mandatory = $true)][string]$Sha,
    [string]$Repo = 'smartboyhacker66-stack/Ndrccrcs',
    [int]$TimeoutMinutes = 25
)

# Polls GitHub Actions for the run that belongs to $Sha and writes its result
# (success / failure / cancelled / timeout / unavailable) to .build_result.txt
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$resultFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.build_result.txt'
$uri = "https://api.github.com/repos/$Repo/actions/runs?head_sha=$Sha"
$headers = @{ 'User-Agent' = 'bits-tandem-push' }
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$result = 'timeout'
$lastStatus = ''

while ((Get-Date) -lt $deadline) {
    try {
        $response = Invoke-RestMethod -Uri $uri -Headers $headers
        if ($response.workflow_runs.Count -gt 0) {
            $run = $response.workflow_runs[0]
            if ($run.status -ne $lastStatus) {
                Write-Host "  GitHub build status: $($run.status)"
                $lastStatus = $run.status
            }
            if ($run.status -eq 'completed') {
                $result = $run.conclusion
                break
            }
        } else {
            Write-Host '  Waiting for GitHub to start the build...'
        }
    } catch {
        Write-Host "  Could not read build status from GitHub: $($_.Exception.Message)"
        $result = 'unavailable'
        break
    }
    Start-Sleep -Seconds 15
}

Set-Content -Path $resultFile -Value $result -Encoding ASCII
