//! Compiles the phrase table and the Rime dictionary into the lexicon the app loads.
//!
//!     cargo run --release --bin compile_lexicon -- \
//!         data/lexicon/mcbopomofo.tsv app/src/main/assets/rime/shared/fs_zhuyin.dict.yaml \
//!         app/src/main/assets/lexicon/lexicon.bin
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let [_, table, dict, out] = args.as_slice() else {
        eprintln!("usage: compile_lexicon <phrase table> <rime dictionary> <output>");
        return ExitCode::FAILURE;
    };
    let read = |path: &str| std::fs::read_to_string(path).map_err(|error| format!("{path}: {error}"));
    let (table, dict) = match (read(table), read(dict)) {
        (Ok(table), Ok(dict)) => (table, dict),
        (Err(error), _) | (_, Err(error)) => {
            eprintln!("{error}");
            return ExitCode::FAILURE;
        }
    };
    let compiled = fs_lexicon::compile(&table, &dict);
    let entries = match fs_lexicon::open(compiled.clone(), "") {
        Ok(lexicon) => lexicon.len(),
        Err(error) => {
            eprintln!("compiled lexicon does not open: {error}");
            return ExitCode::FAILURE;
        }
    };
    if let Some(parent) = std::path::Path::new(out).parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Err(error) = std::fs::write(out, &compiled) {
        eprintln!("{out}: {error}");
        return ExitCode::FAILURE;
    }
    println!("{out}: {entries} entries, {} bytes", compiled.len());
    ExitCode::SUCCESS
}
