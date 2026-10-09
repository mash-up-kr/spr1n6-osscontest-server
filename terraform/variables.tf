variable "aws_region" { type = string }

variable "aws_profile" {
  type    = string
  default = "default"
}

variable "ami_id" {
  type        = string
  description = "Verified Rocky Linux 9.x x86_64 AMI in this region"
}

variable "instance_type" {
  type    = string
  default = "t3.small"
}

variable "key_pair_name" {
  type        = string
  description = "Existing EC2 key pair name (not .pem path)"
}

variable "ssh_cidrs" {
  type        = list(string)
  description = "CIDRs allowed to SSH. [\"0.0.0.0/0\"] opens to all (key-only auth)"
}

variable "associate_public_ip_address" {
  type    = bool
  default = true
}

variable "root_volume_gb" {
  type    = number
  default = 30
}

variable "availability_zone" {
  type    = string
  default = "ap-northeast-2a"
}
