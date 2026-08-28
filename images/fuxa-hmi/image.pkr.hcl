packer {
  required_plugins {
    ansible = {
      version = ">= 1.1.3"
      source  = "github.com/hashicorp/ansible"
    }
  }
}

variable "ansible_host" {
  default = "default"
}

locals {
  version = formatdate("YYYY-MM-DD-hh-mm", timestamp())
}

source "openstack" "fuxa-hmi" {
  flavor                       = "server-small"
  image_name                   = "fuxa-hmi-${local.version}"
  external_source_image_url    = "https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img"
  external_source_image_format = "qcow2"
  ssh_username                 = "ubuntu"
  ssh_pty                      = true
  networks                     = ["39a7e47a-f481-485a-9569-239258173b30"]
  floating_ip_network          = "d118259f-1b00-462a-8293-999e1ddbe43e"
}

build {
  sources = ["source.openstack.fuxa-hmi"]

  provisioner "file" {
    source      = "assets"
    destination = "/tmp"
  }

  provisioner "shell" {
    script = "./setup.sh"
  }

  provisioner "shell" {
    script = "./install.sh"
  }
}
