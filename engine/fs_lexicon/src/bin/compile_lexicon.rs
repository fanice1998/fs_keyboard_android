//! Compiles the phrase table, the Rime dictionary and the bigrams into the lexicon the app loads.
//!
//!     cargo run --release --bin compile_lexicon -- \
//!         data/lexicon/mcbopomofo.tsv app/src/main/assets/rime/shared/fs_zhuyin.dict.yaml \
//!         data/lexicon/bigram.tsv app/src/main/assets/lexicon/lexicon.bin
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let [_, table, dict, bigrams, out] = args.as_slice() else {
        eprintln!("usage: compile_lexicon <phrase table> <rime dictionary> <bigrams> <output>");
        return ExitCode::FAILURE;
    };
    let read = |path: &str| std::fs::read_to_string(path).map_err(|error| format!("{path}: {error}"));
    let (table, dict, bigrams) = match (read(table), read(dict), read(bigrams)) {
        (Ok(table), Ok(dict), Ok(bigrams)) => (table, dict, bigrams),
        (Err(error), _, _) | (_, Err(error), _) | (_, _, Err(error)) => {
            eprintln!("{error}");
            return ExitCode::FAILURE;
        }
    };
    let compiled = fs_lexicon::compile(&table, &dict, &bigrams);
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
