# Single-AZ public subnet for initial 3-node development/test cluster.
resource "aws_vpc" "ha" {
  cidr_block           = "10.40.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(local.common_tags, { Name = "spr1n6-ha-vpc" })
}
resource "aws_subnet" "db" {
  vpc_id                  = aws_vpc.ha.id
  cidr_block              = "10.40.1.0/24"
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = true
  tags                    = merge(local.common_tags, { Name = "spr1n6-ha-public-subnet" })
}
resource "aws_internet_gateway" "ha" {
  vpc_id = aws_vpc.ha.id
  tags   = merge(local.common_tags, { Name = "spr1n6-ha-igw" })
}
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.ha.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.ha.id
  }
  tags = merge(local.common_tags, { Name = "spr1n6-ha-public-rt" })
}
resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.db.id
  route_table_id = aws_route_table.public.id
}
