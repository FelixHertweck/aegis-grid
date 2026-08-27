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

source "openstack" "windows-fileserver" {
  flavor              = "server-windows"
  image_name          = "win-fileserver-${local.version}"
  source_image_name   = "server2k22"
  communicator        = "winrm"
  winrm_username      = "caveadmin"
  winrm_password      = "changeme"
  networks            = ["39a7e47a-f481-485a-9569-239258173b30"]
  floating_ip_network = "d118259f-1b00-462a-8293-999e1ddbe43e"
  security_groups     = ["open"]
  winrm_timeout = "30m"
}

build {
  sources = ["source.openstack.windows-fileserver"]

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
      "-e", "ansible_winrm_operation_timeout_sec=120",
      "-e", "ansible_winrm_read_timeout_sec=150"
    ]
  }

    provisioner "file" {
    source      = "unattend.xml"
    destination = "C:\\Windows\\Panther\\Unattend\\unattend.xml"
  }

  provisioner "windows-shell" {
    inline = [
      "powershell.exe -Command \"Start-Sleep -Seconds 60\"",
      "powershell.exe -Command \"Start-Process -FilePath 'C:\\Windows\\System32\\Sysprep\\Sysprep.exe' -ArgumentList '/oobe /generalize /shutdown /unattend:C:\\Windows\\Panther\\Unattend\\unattend.xml' -Wait\"",
      "powershell.exe -Command \"Start-Sleep -Seconds 60\"",
    ]
  }
}
