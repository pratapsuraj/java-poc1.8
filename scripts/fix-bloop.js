// Run with: node scripts/fix-bloop.js   (from the project root, after every Bloop/Metals "Import build")
//
// Metals compiles the Java module "common" with a "-release 8" flag that JDK 8's javac rejects
// ("bad option: '-release'"), which makes the Scala debug attach fail with "Compilation not successful".
// "common" is already built by Maven and is on spark-job's classpath (common/target/classes),
// so this removes the Bloop project dependency and lets Metals compile only the Scala code.
const fs = require('fs');

const fixes = [['spark-job', []], ['spark-job-test', ['spark-job']]];
for (const [name, deps] of fixes) {
  const file = `.bloop/${name}.json`;
  if (!fs.existsSync(file)) {
    console.error(`Missing ${file}. Run the Bloop import first (see README, "Debug in the IDE").`);
    process.exit(1);
  }
  const json = JSON.parse(fs.readFileSync(file, 'utf8'));
  json.project.dependencies = deps;
  fs.writeFileSync(file, JSON.stringify(json, null, 2));
  console.log(`${name}: dependencies -> ${JSON.stringify(deps)}`);
}
console.log('Done. In VS Code run "Metals: Restart build server".');
