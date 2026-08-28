<#
  Ocelot VM7 — derandomized/fixed AD content for Scenario 3.3.

  Derived from the generic VulnAD.ps1 framework (the same one used to build day4/DC01 and
  day5/01-dc in cave-images), but stripped down and de-randomized on purpose:

  - No random 100-user noise population, no random group/ACL scrambling — Scenario 3.3 only
    needs two specific, reproducible attack paths, not a randomized CTF-style AD lab. The
    generic framework's population/BadAcls helpers are not used here; re-add them later if a
    "realistic AD noise" pass is ever wanted.
  - Path B (Kerberoasting): exactly one fixed, crackable service account, tied to VM1d
    ("Path B SPN service" per ToDo/VMOverview.md). RC4 (msDS-SupportedEncryptionTypes = 4) is
    forced so the TGS is actually crackable — a real DC would otherwise prefer AES.
  - Path C (GPO credential-embedding into VM4/fuxa-hmi) is intentionally NOT implemented here.
    It has no equivalent in the source framework (no GPP/cpassword helper exists in it either),
    and depends on a credential value that images/fuxa-hmi/README.md itself still lists as
    open ("fixed non-default admin credential value"). See this image's README for the plan.
  - DCSync stays disabled: this script never calls a DCSync-rights function (VulnAD-DCSync /
    -specificUser / -specificGroup in the source framework) on any account or group — nothing
    here is granted Replicating Directory Changes / Replicating Directory Changes All.
  - caveadmin de-provisioning on this host happens in domain-promotion.yml (Ansible), not here,
    matching how day4/DC01 and day5/01-dc split that responsibility.
#>

$Global:DomainName = "corp.local"

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

function Invoke-OcelotVulnAD {
    # Path B: the one Kerberoastable service account tied to VM1d.
    VulnAD-Kerberoasting
    Write-Good "Path B (Kerberoasting) account created"

    Start-Sleep -Seconds 5

    # Tier-0 account for CAVE/deploy tooling — used to join VM1/VM1c/VM1d/VM1e to the domain
    # and for any other post-promotion AD management. caveadmin gets disabled on this host
    # once this account exists (see domain-promotion.yml).
    New-DomainAdmin -Username "ad_admin" -Password "Hd4mNq2VbXtRfW9j!"
    Start-Sleep -Seconds 5

    # Deliberately not called: any DCSync-granting function. DCSync stays disabled for every
    # account/group created by this script.
}

Invoke-OcelotVulnAD
