#!/usr/bin/env ruby

$config_template = <<-CONFIG_TEMPLATE
eclair.chain=#CHAIN#
eclair.node-alias=#ALIAS#
eclair.enable-db-backup=false
eclair.server.public-ips.1=127.0.0.1
eclair.server.port=#PORT#
eclair.api.enabled=true
eclair.api.password=bar
eclair.api.port=#API_PORT#
eclair.bitcoind.port=18444
eclair.bitcoind.rpcport=18443
eclair.bitcoind.zmqblock="tcp://127.0.0.1:29000"
eclair.bitcoind.zmqtx="tcp://127.0.0.1:29001"
eclair.mindepth-blocks=1
eclair.max-htlc-value-in-flight-msat=100000000000
eclair.router.broadcast-interval=2 seconds
eclair.to-remote-delay-blocks=24
eclair.multi-part-payment-expiry=20 seconds
eclair.features.basic_mpp=disabled
eclair.features.option_data_loss_protect=optional
eclair.features.initial_routing_sync=optional
eclair.features.gossip_queries=optional
eclair.features.gossip_queries_ex=optional
eclair.features.var_onion_optin=optional
eclair.features.ptlc=optional
CONFIG_TEMPLATE

def print_help
    puts "#{$0} (init|start|stop) [options]"
    puts "\tinit\tInitialize the network setup"
    puts "\tstart\tStart the network"
    puts "\tstop\tstop the network"
    exit
end

def print_init_help
    puts "usage: #{$0} init [options]"
    puts "\t--num-nodes=<2-26>\t\t\tNumber of nodes (default 3) "
    puts "\t--eclair-zip=<path>\t\t\tPath to Eclair distribution zip file"
    puts "\t--work-dir=<path>\t\t\tPath to the work directory"
    puts "\t--chain=<regtest|testnet|mainnet>\tBitcoin chain"
    exit
end

def print_start_help
    puts "usage: #{$0} start [options]"
    puts "\t--work-dir=<path>\t\t\tPath to the work directory"
    exit
end

def print_stop_help
    puts "usage: #{$0} stop [options]"
    puts "\t--work-dir=<path>\t\t\tPath to the work directory"
    exit
end

def process_argv_option(option, options, print_help)
  opt = option.split("=")
  case opt.first
  when "-h"
    print_help
  when "--help"
    print_help
  when "--num-nodes"
    self.send(print_help) if opt.size != 2
    options[:num_nodes] = opt.last.to_i
    self.send(print_help) if options[:num_nodes] < 2
  when "--chain"
    options[:chain] = true
    self.send(print_help) if opt.size != 2
    chain = opt.last
    self.send(print_help) unless ["regtest", "testnet", "mainnet"].include?(chain)
    options[:chain] = chain
  when "--eclair-zip"
    self.send(print_help) if opt.size != 2
    options[:eclair_zip] = opt.last
  when "--work-dir"
    self.send(print_help) if opt.size != 2
    options[:work_dir] = opt.last
  end
end

def find_eclair_zip
  dirs = [".", "eclair-node/target", "../eclair-node/target"]
  dirs.each do |top_dir|
    files = Dir.glob("#{top_dir}/eclair-node-*-bin.zip")
    return files.first if not files.empty?
  end
  nil
end

def process_init_argv(argv)
    options = {}

    argv.each { |option| process_argv_option(option, options, :print_init_help) }

    options[:num_nodes] = 3 if options[:num_nodes].nil?

    options[:chain] = "regtest" if options[:chain].nil?

    options[:work_dir] = "." if options[:work_dir].nil?

    if options[:eclair_zip].nil?
      zip = find_eclair_zip
      unless zip.nil?
        options[:eclair_zip] = zip
      else
        puts "Use --eclair-zip to specify path to Eclair distribution zip file"
        exit
      end
    end

    options
end

def process_argv(argv, print_help)
    options = {}
    argv.each { |option| process_argv_option(option, options, print_help) }
    options[:work_dir] = "." if options[:work_dir].nil?
    options
end

def process_start_argv(argv)
  process_argv(argv, :print_start_help)
end

def process_stop_argv(argv)
  process_argv(argv, :print_stop_help)
end

def get_launch_script(args)
    script = Dir.glob("#{args[:work_dir]}/eclair-node-*/bin/eclair-node.sh").first
    if script.nil?
        puts "Cannot find eclair launch script"
        exit
    end
    script
end

def get_cli_script(args)
    script = Dir.glob("#{args[:work_dir]}/eclair-node-*/bin/eclair-cli").first
    if script.nil?
        puts "Cannot find eclair CLI script"
        exit
    end
    File.absolute_path(script)
end

def init(args)
  puts "Unzipping #{args[:eclair_zip]}"
  unless system("unzip #{args[:eclair_zip]} > /dev/null")
    puts "Cannot unzip #{args[:eclair_zip]}: error code #{$?}"
    exit
  end

  bin_dir = args[:work_dir]
  Dir.mkdir(bin_dir) unless Dir.exist?(bin_dir)

  cli_script = get_cli_script(args)

  nodes_root_dir = "#{args[:work_dir]}/nodes"
  Dir.mkdir(nodes_root_dir) unless Dir.exist?(nodes_root_dir)

  node_dirs = (0..(args[:num_nodes] - 1)).map { |i| "#{nodes_root_dir}/#{(65 + i).chr}" }
  node_dirs.each { |dir| Dir.mkdir(dir)  unless Dir.exist?(dir) }

  node_dirs.zip((0..(args[:num_nodes] - 1)).map { |i| i}).each do |dir, i|
    node_alias = dir.split("/").last
    port = 9835 + i
    api_port = 8800 + i

    conf = $config_template
      .sub('#CHAIN#', args[:chain])
      .sub('#ALIAS#', node_alias)
      .sub('#PORT#', port.to_s)
      .sub('#API_PORT#', api_port.to_s)

    File.open("#{dir}/eclair.conf", 'w') do |file|
      file.write(conf)
    end

    cli = "#{bin_dir}/eclair-cli-#{(97 + i).chr}"

    f = File.new(cli, "w");
    f.chmod(0755)

    File.open(cli, 'w') do |file|
      file.write('#!/bin/bash' +
      "\n\n#{cli_script} -p bar -a 127.0.0.1:#{api_port} $1 $2 $3 $4 $5 $6 $7 $8 $9")
    end
  end
end

def pid_file(args)
  "#{args[:work_dir]}/nodes/pids"
end

def start(args)
    script = get_launch_script(args)
    node_dirs = Dir.glob("#{args[:work_dir]}/nodes/*")

    pids = []

    node_dirs.each do |dir|
        ENV['DISABLE_SECP256K1'] = 'true'
        cmd = "#{script} -Declair.datadir=#{dir}"
        puts "Starting #{cmd}"
        pid = fork { exec(cmd) }
        pids << pid
    end

    File.open(pid_file(args), 'w') do |file|
        pids.each { |pid| file.write("#{pid}\n") }
    end
end

def stop(args)
    pids = []
    if File.exist?(pid_file(args))
        IO.readlines(pid_file(args)).each do |line|
            pids << line.chomp.to_i
        end
        pids.each do |pid|
            puts "Stopping process #{pid}"
            Process.kill("HUP", pid)
        end
        File.delete(pid_file(args))
    end
end

command, *argv = ARGV
case command
when "init"
  args = process_init_argv(argv)
  init(args)
when "start"
  args = process_start_argv(argv)
  start(args)
when "stop"
  args = process_stop_argv(argv)
  stop(args)
else
  print_help
end
