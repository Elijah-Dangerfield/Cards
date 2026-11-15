require "minitest/autorun"
require "tmpdir"
require "fileutils"
require "shellwords"

FASTFILE_SOURCE = File.read(File.expand_path("../../apps/ios/fastlane/Fastfile", __dir__))
OUTPUT_DIR = "build"

module UI
  def self.important(message)
    (@messages ||= []) << message
  end

  def self.messages
    @messages ||= []
  end
end

class FastfileSentryTest < Minitest::Test
  def setup
    @prior_env = ENV.to_h
    ENV.update("SENTRY_AUTH_TOKEN" => "test-only", "SENTRY_ORG" => "org with spaces", "SENTRY_PROJECT" => "test-project")
    @root = Dir.mktmpdir("ios output with spaces ")
    FileUtils.mkdir_p(File.join(@root, "fastlane"))
    FileUtils.mkdir_p(File.join(@root, "build"))
    @helper = Object.new
    @helper.instance_eval(FASTFILE_SOURCE[FASTFILE_SOURCE.index("def upload_sentry_release")..], File.join(@root, "fastlane", "Fastfile"))
    @commands = []
    commands = @commands
    @helper.define_singleton_method(:sh) { |*args| commands << args }
    UI.messages.clear
  end

  def teardown
    ENV.replace(@prior_env)
    FileUtils.remove_entry(@root)
  end

  def test_uploads_exact_absolute_archive_from_fastlane_working_directory
    artifact = File.join(@root, "build", "Cards.app.dSYM.zip")
    File.write(artifact, "fixture")
    Dir.chdir(File.join(@root, "fastlane")) { @helper.upload_sentry_release("0.9.0", "1388") }
    assert_equal ["npx", "@sentry/cli", "debug-files", "upload", "--org", "org with spaces", "--project", "test-project", artifact], @commands[2]
    assert_includes @commands[0], "cards@0.9.0+1388"
    assert_equal ["npx", "@sentry/cli", "releases", "set-commits", "cards@0.9.0+1388", "--auto", "--org", "org with spaces", "--project", "test-project", "||", "true"], Shellwords.split(@commands[1][0])
    assert_empty UI.messages
  end

  def test_accepts_unzipped_bundle
    artifact = File.join(@root, "build", "Cards.app.dSYM")
    FileUtils.mkdir_p(artifact)
    @helper.upload_sentry_release("0.9.0", "1388")
    assert_equal artifact, @commands[2].last
  end

  def test_missing_artifact_is_reported_without_upload_and_remains_nonfatal
    @helper.upload_sentry_release("0.9.0", "1388")
    assert_empty @commands
    assert_match(/No dSYM archive or bundle found in .*build.*non-fatal/, UI.messages.fetch(0))
  end

  def test_upload_failure_remains_nonfatal
    File.write(File.join(@root, "build", "Cards.app.dSYM.zip"), "fixture")
    @helper.define_singleton_method(:sh) { |*_args| raise "upload failed" }
    @helper.upload_sentry_release("0.9.0", "1388")
    assert_match(/upload failed.*non-fatal/, UI.messages.fetch(0))
  end

  def test_without_token_skips_upload
    ENV.delete("SENTRY_AUTH_TOKEN")
    @helper.upload_sentry_release("0.9.0", "1388")
    assert_empty @commands
    assert_empty UI.messages
  end

  def test_release_call_uses_metadata_override_or_commit_count_never_store_timestamp
    call = FASTFILE_SOURCE.lines.find { |line| line.start_with?("  upload_sentry_release(") }
    @helper.define_singleton_method(:upload_sentry_release) { |*args| args }
    context = @helper.instance_eval { binding }
    context.local_variable_set(:marketing_version, "0.9.0")
    context.local_variable_set(:build_number, "202611151656")
    context.local_variable_set(:meta_build_number, "1388")
    ENV.delete("BUILD_NUMBER_OVERRIDE")
    assert_equal ["0.9.0", "1388"], eval(call, context)
    ENV["BUILD_NUMBER_OVERRIDE"] = "1390"
    assert_equal ["0.9.0", "1390"], eval(call, context)
  end
end
