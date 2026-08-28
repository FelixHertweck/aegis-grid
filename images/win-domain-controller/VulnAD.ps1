<#
  Fixed, non-random AD content for corp.local. Derived from the VulnAD framework (as used
  by cave-images day4/DC01, day5/01-dc) but stripped of its randomization — the generic
  script scatters ~100 users/groups/ACLs per run, which a reproducible testbed can't use.

  Creates: a plain domain user (user1); a Kerberoastable service account (svc_helpdesk,
  RC4-forced); the FS-Readers group; svc_fuxa + a GPO carrying a GPP cpassword (MS14-025);
  and ad_admin (a Domain Admin for deploy tooling).

  DCSync rights are never granted — the framework's DCSync functions are not called.
  caveadmin de-provisioning happens in domain-promotion.yml, not here.
#>

$Global:DomainName = "corp.local"

# svc_fuxa's password. Must equal the FUXA admin login set on the fuxa-hmi host.
$Global:FuxaAdminPassword = "Eyta564EGS9iXDEcbD!"

function Write-Good { param( $String ) Write-Host "[+] $String" -ForegroundColor 'Green' }
function Write-Bad  { param( $String ) Write-Host "[-] $String" -ForegroundColor 'Red'   }
function Write-Info { param( $String ) Write-Host "[*] $String" -ForegroundColor 'Gray'  }

# Type 4 = RC4 only (deliberately crackable), 8 = AES128, 16 = AES256
$Global:ServicesAccountsAndSPNs = @(
    [PSCustomObject]@{Svc='svc_helpdesk'; Service='HTTP'; Hostname='vm1d.corp.local'; Password='Support2024!'; Type=4}
)

function VulnAD-Kerberoasting {
    foreach ($sv in $Global:ServicesAccountsAndSPNs) {
        $svc      = $sv.Svc
        $hostname = $sv.Hostname
        $service  = $sv.Service
        $password = $sv.Password
        $etype    = $sv.Type

        $spn_full = "$service/$hostname"

        Write-Info "Kerberoasting $svc ($spn_full)"

        Try {
            $newUser = New-ADUser `
                        -Name $svc `
                        -SamAccountName $svc `
                        -UserPrincipalName "$svc@$Global:DomainName" `
                        -AccountPassword (ConvertTo-SecureString $password -AsPlainText -Force) `
                        -PassThru

            Enable-ADAccount -Identity $newUser

            Set-ADUser -Identity $newUser -ServicePrincipalNames @{Add=$spn_full}
            Set-ADUser -Identity $newUser -Replace @{ 'msDS-SupportedEncryptionTypes' = [int]$etype }
            Write-Info "Set msDS-SupportedEncryptionTypes = $etype for $svc"
        } Catch {
            Write-Bad "Could not create Kerberoastable account $svc ($spn_full): $_"
        }
    }
}

function New-FsReadersGroup {
    # svc_helpdesk's group. win-fileserver/domain-join.yml grants it (and only it) read
    # on \\FS01\Shared, so the share is reachable only after cracking svc_helpdesk.
    $group = 'FS-Readers'
    if (Get-ADGroup -Filter { Name -eq $group } -ErrorAction SilentlyContinue) {
        Write-Bad "Group '$group' already exists."
    } else {
        New-ADGroup -Name $group -GroupScope Global -GroupCategory Security `
            -Description 'Read access to \\FS01\Shared'
        Write-Good "Group '$group' created"
    }
    Add-ADGroupMember -Identity $group -Members 'svc_helpdesk'
    Write-Good "svc_helpdesk added to '$group'"
}

function New-FootholdUser {
    # A plain domain user: Domain Users only, no privileged group, no SPN.
    # win11-workstation/ad-foothold.yml gives it RDP/WinRM but not local admin.
    param (
        [string]$Username = "user1",
        [string]$Password = "Welcome123!",
        [string]$GivenName = "Erika",
        [string]$Surname = "Mustermann"
    )

    if (Get-ADUser -Filter {SamAccountName -eq $Username}) {
        Write-Bad "User '$Username' already exists."
        return
    }

    New-ADUser `
        -Name "$GivenName $Surname" `
        -GivenName $GivenName `
        -Surname $Surname `
        -SamAccountName $Username `
        -UserPrincipalName "$Username@$((Get-ADDomain).DNSRoot)" `
        -AccountPassword (ConvertTo-SecureString $Password -AsPlainText -Force) `
        -Enabled $true

    Write-Good "Foothold user '$Username' created and enabled."
}

function New-DomainAdmin {
    param (
        [Parameter(Mandatory=$true)]
        [string]$Username,

        [Parameter(Mandatory=$true)]
        [string]$Password,

        [Parameter(Mandatory=$false)]
        [string]$GivenName = "AD",

        [Parameter(Mandatory=$false)]
        [string]$Surname = "Admin"
    )

    if (Get-ADUser -Filter {SamAccountName -eq $Username}) {
        Write-Bad "User '$Username' already exists."
        return
    }

    $securePass = ConvertTo-SecureString $Password -AsPlainText -Force

    $newUser = New-ADUser `
        -Name "$GivenName $Surname" `
        -GivenName $GivenName `
        -Surname $Surname `
        -SamAccountName $Username `
        -UserPrincipalName "$Username@$((Get-ADDomain).DNSRoot)" `
        -AccountPassword $securePass `
        -Enabled $true `
        -PassThru

    Add-ADGroupMember -Identity "Domain Admins" -Members $newUser

    Write-Good "Domain Admin '$Username' created and enabled."
    return $newUser
}

function ConvertTo-GppCpassword {
    # MS-GPPREF 2.2.1.1: AES-256-CBC with Microsoft's published key, zero IV, UTF-16LE
    # plaintext, base64. The key being public is the vulnerability — Get-GPPPassword /
    # gpp-decrypt / nxc --gpp-password all use it to decrypt.
    param([Parameter(Mandatory)][string]$Plain)
    $key = [byte[]](0x4e,0x99,0x06,0xe8,0xfc,0xb6,0x6c,0xc9,0xfa,0xf4,0x93,0x10,0x62,0x0f,0xfe,0xe8,
                    0xf4,0x96,0xe8,0x06,0xcc,0x05,0x79,0x90,0x20,0x9b,0x09,0xa4,0x33,0xb6,0x6c,0x1b)
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.Key     = $key
    $aes.IV      = New-Object byte[] 16
    $aes.Mode    = [System.Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [System.Security.Cryptography.PaddingMode]::PKCS7
    try {
        $pt = [System.Text.Encoding]::Unicode.GetBytes($Plain)
        $ct = $aes.CreateEncryptor().TransformFinalBlock($pt, 0, $pt.Length)
        return [System.Convert]::ToBase64String($ct)
    } finally {
        $aes.Dispose()
    }
}

function New-FuxaProvisioningGpo {
    # A GPO whose SYSVOL GPP Scheduled-Task item stores svc_fuxa's password as a cpassword:
    # readable by any authenticated user (world-readable SYSVOL), decryptable with the
    # published key. GPO permissions are left default — a write grant isn't needed to
    # recover a cpassword. The FUXA host never applies this GPO; it's a credential store
    # dressed as a provisioning artifact.
    Import-Module GroupPolicy -ErrorAction Stop

    $fuxaPwd = $Global:FuxaAdminPassword
    $domain  = $Global:DomainName
    $adDom   = Get-ADDomain
    $dn      = $adDom.DistinguishedName
    $nb      = $adDom.NetBIOSName          # "CORP"

    if (Get-ADUser -Filter { SamAccountName -eq 'svc_fuxa' } -ErrorAction SilentlyContinue) {
        Write-Bad "svc_fuxa already exists."
    } else {
        New-ADUser -Name 'svc_fuxa' -SamAccountName 'svc_fuxa' `
            -UserPrincipalName "svc_fuxa@$domain" `
            -AccountPassword (ConvertTo-SecureString $fuxaPwd -AsPlainText -Force) `
            -Enabled $true -PasswordNeverExpires $true `
            -Description 'FUXA HMI provisioning task account'
        Write-Good "Service account svc_fuxa created (password reused as the FUXA admin login)"
    }

    $gpoName = 'FUXA HMI Provisioning'
    $gpo = Get-GPO -Name $gpoName -ErrorAction SilentlyContinue
    if (-not $gpo) {
        $gpo = New-GPO -Name $gpoName -Comment 'Keeps the FUXA HMI admin account in sync with svc_fuxa'
        New-GPLink -Name $gpoName -Target $dn -LinkEnabled Yes | Out-Null
        Write-Good "GPO '$gpoName' created and linked to $dn"
    }

    $stDir = "\\$domain\SYSVOL\$domain\Policies\{$($gpo.Id)}\Machine\Preferences\ScheduledTasks"
    New-Item -ItemType Directory -Path $stDir -Force | Out-Null

    $cpw = ConvertTo-GppCpassword $fuxaPwd
    $uid = "{$([guid]::NewGuid())}"
    $xml = @"
<?xml version="1.0" encoding="utf-8"?>
<ScheduledTasks clsid="{CC63F200-7309-4ba0-B154-A71CD118DBCC}">
  <Task clsid="{2DEECB1C-261F-4614-A4C2-B02EB21EC80C}" name="Provision FUXA HMI" image="0" changed="2026-01-01 12:00:00" uid="$uid">
    <Properties action="C" name="Provision FUXA HMI" runAs="$nb\svc_fuxa" cpassword="$cpw" logonType="Password">
      <Task>
        <Triggers><LogonTrigger><Enabled>true</Enabled></LogonTrigger></Triggers>
        <Actions>
          <Exec>
            <Command>powershell.exe</Command>
            <Arguments>-NoProfile -ExecutionPolicy Bypass -File \\$domain\SYSVOL\$domain\scripts\provision-fuxa.ps1</Arguments>
          </Exec>
        </Actions>
      </Task>
    </Properties>
  </Task>
</ScheduledTasks>
"@
    Set-Content -Path (Join-Path $stDir 'ScheduledTasks.xml') -Value $xml -Encoding UTF8

    # The referenced script carries no plaintext credential — it would run under the
    # svc_fuxa identity the cpassword unlocks, so decrypting the cpassword is the only way
    # to recover the secret.
    $scriptsDir = "\\$domain\SYSVOL\$domain\scripts"
    New-Item -ItemType Directory -Path $scriptsDir -Force | Out-Null
    $prov = @'
# Deployed via GPO 'FUXA HMI Provisioning'. Runs as CORP\svc_fuxa on management hosts.
# Keeps the FUXA HMI admin account in sync with this service account. The credential
# comes from the scheduled task's runAs context, not from this file.
$ErrorActionPreference = 'Stop'
$FuxaUrl = 'http://10.1.2.10:1881'
Write-Output "FUXA provisioning check against $FuxaUrl complete."
'@
    Set-Content -Path (Join-Path $scriptsDir 'provision-fuxa.ps1') -Value $prov -Encoding UTF8

    Write-Good "GPP cpassword staged for '$gpoName' in SYSVOL"
}

function Invoke-OcelotVulnAD {
    New-FootholdUser
    Start-Sleep -Seconds 5

    # Kerberoastable account + the FS-Readers group that its crack unlocks.
    VulnAD-Kerberoasting
    Write-Good "Kerberoastable account created"
    Start-Sleep -Seconds 5
    New-FsReadersGroup
    Start-Sleep -Seconds 5

    New-FuxaProvisioningGpo
    Start-Sleep -Seconds 5

    # Domain Admin for deploy tooling (the domain-join.yml playbooks authenticate as this).
    # caveadmin is disabled on this host once it exists (see domain-promotion.yml).
    New-DomainAdmin -Username "ad_admin" -Password "Hd4mNq2VbXtRfW9j!"
    Start-Sleep -Seconds 5

    # No DCSync-granting function is ever called.
}

Invoke-OcelotVulnAD
