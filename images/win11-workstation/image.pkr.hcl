packer {
  required_plugins {
    openstack = {
      version = ">= 1.1.2"
      source  = "github.com/hashicorp/openstack"
    }
    ansible = {
      version = ">= 1.0.0"
      source  = "github.com/hashicorp/ansible"
    }
  }
}

locals {
  version = formatdate("YYYY-MM-DD-hh-mm",timestamp())
}

source "openstack" "windows-client" {
  flavor              = "server-windows"
  image_name          = "win11-workstation-${local.version}"
  source_image_name   = "client_win11"
  communicator        = "winrm"
  winrm_username      = "caveadmin"
  winrm_password      = "changeme"
  networks            = ["39a7e47a-f481-485a-9569-239258173b30"]
  floating_ip_network = "d118259f-1b00-462a-8293-999e1ddbe43e"
  security_groups     = ["open"]
  winrm_timeout = "30m"
}

build {
  sources = ["source.openstack.windows-client"]

  provisioner "ansible" {
    playbook_file = "playbook.yml"
    user          = "caveadmin"
    use_proxy = false
    extra_arguments = [
      "-f", "1",
      "-c", "winrm",
      "-e", "ansible_password=changeme",
      "-e", "ansible_shell_type=cmd",
      "-e", "ansible_winrm_transport=ntlm",
      "-e", "ansible_winrm_server_cert_validation=ignore",
      "-e", "ansible_port=5985",
      "-e", "ansible_winrm_scheme=http",
      "-e", "ansible_winrm_operation_timeout_sec=45",
      "-e", "ansible_winrm_read_timeout_sec=70"
    ]
  }

    provisioner "file" {
    source      = "unattend.xml"
    destination = "C:\\Windows\\Panther\\Unattend\\unattend.xml"
  }

  provisioner "windows-shell" {
    inline = [
      "powershell.exe -Command \"Start-Sleep -Seconds 60\"",
      # Sysprep hard-fails generalize if the OS volume is encrypted (base image
      # picks up an orphaned BitLocker/Device Encryption state - no key protector,
      # protection off, but VolumeStatus stays FullyEncrypted until explicitly
      # decrypted). Left running, Sysprep hangs indefinitely on an invisible error
      # dialog instead of exiting - bounded wait here so a real problem fails loud.
      "powershell.exe -Command \"$vol = Get-BitLockerVolume -MountPoint C:; if ($vol.VolumeStatus -ne 'FullyDecrypted') { Disable-BitLocker -MountPoint C: | Out-Null; $t = 3600; while ($t -gt 0 -and $vol.VolumeStatus -ne 'FullyDecrypted') { Start-Sleep -Seconds 10; $t -= 10; $vol = Get-BitLockerVolume -MountPoint C:; Write-Output ('BitLocker ' + $vol.VolumeStatus + ' ' + $vol.EncryptionPercentage + '%') }; if ($vol.VolumeStatus -ne 'FullyDecrypted') { Write-Error 'BitLocker did not finish decrypting before timeout'; exit 1 } }\"",
      "powershell.exe -Command \"Start-Process -FilePath 'C:\\Windows\\System32\\Sysprep\\Sysprep.exe' -ArgumentList '/oobe /generalize /shutdown /unattend:C:\\Windows\\Panther\\Unattend\\unattend.xml' -Wait\"",
      "powershell.exe -Command \"Start-Sleep -Seconds 60\"",
    ]
  }
}
