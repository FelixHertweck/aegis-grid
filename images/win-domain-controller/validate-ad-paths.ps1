<#
  Post-promotion check for VulnAD.ps1's output. Run on the promoted DC. Verifies each
  seeded object is correct (svc_helpdesk SPN + RC4, FS-Readers membership, the SYSVOL
  cpassword decrypting to the expected value, svc_fuxa, user1 unprivileged) and that no
  seeded principal holds DCSync rights. Exits non-zero on any failure.

      powershell -ExecutionPolicy Bypass -File .\validate-ad-paths.ps1
      powershell -ExecutionPolicy Bypass -File .\validate-ad-paths.ps1 -FuxaUrl http://<fuxa-host>:1881

  -FuxaUrl (optional): also confirms the recovered password logs into FUXA and 123456 does
  not. Skip if this host can't reach the FUXA host.
#>
[CmdletBinding()]
param(
    # Must match $Global:FuxaAdminPassword in VulnAD.ps1.
    [string]$ExpectedFuxaPassword = 'Eyta564EGS9iXDEcbD!',
    [string]$FuxaUrl
)

$ErrorActionPreference = 'Stop'
Import-Module ActiveDirectory
Import-Module GroupPolicy

$fail = New-Object System.Collections.Generic.List[string]
function Check($ok, $msg) {
    if ($ok) { Write-Host "[+] $msg" -ForegroundColor Green }
    else     { Write-Host "[-] $msg" -ForegroundColor Red; $fail.Add($msg) }
}

$gppKey = [byte[]](0x4e,0x99,0x06,0xe8,0xfc,0xb6,0x6c,0xc9,0xfa,0xf4,0x93,0x10,0x62,0x0f,0xfe,0xe8,
                   0xf4,0x96,0xe8,0x06,0xcc,0x05,0x79,0x90,0x20,0x9b,0x09,0xa4,0x33,0xb6,0x6c,0x1b)
function ConvertFrom-GppCpassword {
    param([Parameter(Mandatory)][string]$Cpassword)
    $pad = (4 - ($Cpassword.Length % 4)) % 4
    $b64 = $Cpassword + ('=' * $pad)
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.Key = $gppKey; $aes.IV = New-Object byte[] 16
    $aes.Mode = [System.Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [System.Security.Cryptography.PaddingMode]::PKCS7
    try {
        $ct = [System.Convert]::FromBase64String($b64)
        $pt = $aes.CreateDecryptor().TransformFinalBlock($ct, 0, $ct.Length)
        [System.Text.Encoding]::Unicode.GetString($pt)
    } finally { $aes.Dispose() }
}

$domain = (Get-ADDomain).DNSRoot

Write-Host "`n== Kerberoastable account -> file share ==" -ForegroundColor Cyan

$svc = Get-ADUser -Filter { SamAccountName -eq 'svc_helpdesk' } -Properties ServicePrincipalName, 'msDS-SupportedEncryptionTypes' -ErrorAction SilentlyContinue
Check ($null -ne $svc) "svc_helpdesk exists"
if ($svc) {
    Check ($svc.ServicePrincipalName -contains 'HTTP/vm1d.corp.local') "svc_helpdesk has SPN HTTP/vm1d.corp.local"
    Check ($svc.'msDS-SupportedEncryptionTypes' -eq 4) "svc_helpdesk forced to RC4 (msDS-SupportedEncryptionTypes = 4)"
}

$grp = Get-ADGroup -Filter { Name -eq 'FS-Readers' } -ErrorAction SilentlyContinue
Check ($null -ne $grp) "FS-Readers group exists"
if ($grp) {
    $members = Get-ADGroupMember -Identity 'FS-Readers' | Select-Object -ExpandProperty SamAccountName
    Check ($members -contains 'svc_helpdesk') "svc_helpdesk is a member of FS-Readers"
}

$u1 = Get-ADUser -Filter { SamAccountName -eq 'user1' } -Properties MemberOf -ErrorAction SilentlyContinue
Check ($null -ne $u1) "user1 exists"
if ($u1) {
    $privileged = @('FS-Readers','Domain Admins','Administrators','Account Operators','Server Operators')
    $bad = $u1.MemberOf | ForEach-Object { (Get-ADGroup $_).Name } | Where-Object { $privileged -contains $_ }
    Check ($null -eq $bad) "user1 is a plain domain user (not in $($privileged -join ', '))"
}

Write-Host "`n== GPP cpassword -> FUXA login ==" -ForegroundColor Cyan

$fuxaSvc = Get-ADUser -Filter { SamAccountName -eq 'svc_fuxa' } -ErrorAction SilentlyContinue
Check ($null -ne $fuxaSvc) "svc_fuxa exists"

$gpo = Get-GPO -Name 'FUXA HMI Provisioning' -ErrorAction SilentlyContinue
Check ($null -ne $gpo) "GPO 'FUXA HMI Provisioning' exists"
$recovered = $null
if ($gpo) {
    $xmlPath = "\\$domain\SYSVOL\$domain\Policies\{$($gpo.Id)}\Machine\Preferences\ScheduledTasks\ScheduledTasks.xml"
    Check (Test-Path $xmlPath) "ScheduledTasks.xml present in the GPO's SYSVOL folder"
    if (Test-Path $xmlPath) {
        $cp = ([xml](Get-Content -Raw $xmlPath)).ScheduledTasks.Task.Properties.cpassword
        Check (-not [string]::IsNullOrWhiteSpace($cp)) "cpassword attribute present"
        if ($cp) {
            $recovered = ConvertFrom-GppCpassword $cp
            Check ($recovered -eq $ExpectedFuxaPassword) "cpassword decrypts to the expected FUXA password"
        }
        $script = "\\$domain\SYSVOL\$domain\scripts\provision-fuxa.ps1"
        if (Test-Path $script) {
            $body = Get-Content -Raw $script
            Check (-not ($body -match [regex]::Escape($ExpectedFuxaPassword))) "provision-fuxa.ps1 does not leak the password in plaintext"
        }
    }
}

Write-Host "`n== DCSync ==" -ForegroundColor Cyan

$replGuids = @([guid]'1131f6aa-9c07-11d1-f79f-00c04fc2dcd2', [guid]'1131f6ad-9c07-11d1-f79f-00c04fc2dcd2')
$domAcl = (Get-Acl "AD:$((Get-ADDomain).DistinguishedName)").Access
$seededPrincipals = 'user1','svc_helpdesk','svc_fuxa'
$dcsyncHolders = $domAcl |
    Where-Object { $_.ActiveDirectoryRights -match 'ExtendedRight' -and $replGuids -contains $_.ObjectType } |
    Select-Object -ExpandProperty IdentityReference -Unique |
    Where-Object { $sp = ($_ -replace '^.*\\',''); $seededPrincipals -contains $sp }
Check ($null -eq $dcsyncHolders) "no seeded principal (user1 / svc_helpdesk / svc_fuxa) has replication rights"

if ($FuxaUrl) {
    Write-Host "`n== FUXA reachability ($FuxaUrl) ==" -ForegroundColor Cyan
    $pw = if ($recovered) { $recovered } else { $ExpectedFuxaPassword }
    try {
        $r = Invoke-RestMethod -Uri "$FuxaUrl/api/signin" -Method Post -ContentType 'application/json' `
             -Body (@{ username = 'admin'; password = $pw } | ConvertTo-Json)
        Check ($r.status -eq 'success') "recovered password authenticates to FUXA as admin"
    } catch { Check $false "recovered password authenticates to FUXA as admin ($_)" }
    try {
        Invoke-RestMethod -Uri "$FuxaUrl/api/signin" -Method Post -ContentType 'application/json' `
            -Body (@{ username = 'admin'; password = '123456' } | ConvertTo-Json) | Out-Null
        Check $false "FUXA factory default 123456 is rejected"
    } catch { Check $true "FUXA factory default 123456 is rejected" }
}

Write-Host ""
if ($fail.Count) {
    Write-Host "VALIDATION FAILED ($($fail.Count)):" -ForegroundColor Red
    $fail | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "All checks passed." -ForegroundColor Green
exit 0
