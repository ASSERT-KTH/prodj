<div align="center">
  <h1>ProDJ</h1>
</div>

`ProDJ` is a tool for serializing Java objects to plain code.
It uses these capabilities to automatically generate test-cases from a
production workload.

## Setup
The easiest way to get an executable version of `ProDJ` is to use the provided
`flake.nix`:
1. Enter a dev-shell using `nix develop`
2. Run `java -jar rockstofetch/target/rockstofetch.jar --statistics <config file>`.
   You can find example config files in `rockstofetch/src/test/resources/`.
