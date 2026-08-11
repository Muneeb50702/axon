// gbnf-check — validate a GBNF grammar with llama.cpp's own parser.
//
// `ActionGrammarTest` in :core checks that the grammar and the Kotlin model
// describe the same action space, but it cannot check that llama.cpp will
// *accept* the grammar — that needs llama.cpp's parser. Phase 1 promised to
// close that gap and this is it.
//
// The gap was not hypothetical. The §10.6 grammar failed to parse on-device and
// the only diagnostic available was the five words llama.cpp routes through its
// log callback; the actual reason goes to stderr, which Android discards. This
// tool runs the same parser on the host, where stderr is readable, and turns a
// silent runtime failure into a build-time error with a line number.
//
// Deliberately links the *internal* parser (src/llama-grammar.h) rather than the
// public llama_sampler_init_grammar, because the public entry point needs a
// llama_vocab and therefore a multi-gigabyte model file. Grammar syntax has
// nothing to do with any particular model, so requiring one would keep this out
// of CI for no reason.
//
//   gbnf-check <file.gbnf> [root-symbol]

#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

#include "llama-grammar.h"

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: gbnf-check <file.gbnf> [root-symbol]\n");
        return 2;
    }

    const std::string path = argv[1];
    const std::string root = (argc > 2) ? argv[2] : "root";

    std::ifstream in(path);
    if (!in) {
        std::fprintf(stderr, "gbnf-check: cannot open %s\n", path.c_str());
        return 2;
    }
    std::stringstream ss;
    ss << in.rdbuf();
    const std::string src = ss.str();

    // No vocab: this validates syntax and rule resolution, which is what breaks.
    llama_grammar_parser parser(nullptr);

    if (!parser.parse(src.c_str()) || parser.rules.empty()) {
        std::fprintf(stderr, "\ngbnf-check: FAILED to parse %s\n", path.c_str());
        return 1;
    }

    if (parser.symbol_ids.find(root) == parser.symbol_ids.end()) {
        std::fprintf(stderr, "gbnf-check: grammar has no '%s' symbol\n", root.c_str());
        return 1;
    }

    std::printf("gbnf-check: OK — %s parses, %zu rules, root '%s'\n",
                path.c_str(), parser.rules.size(), root.c_str());
    return 0;
}
