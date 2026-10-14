package dev.glowcow.mnt.view.code

import dev.glowcow.mnt.files.Paths

/** The languages the viewer colours, and which of them a file is written in. */
object Languages {
    private const val C_TYPES = "void char short int long float double signed unsigned bool size_t ssize_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t uintptr_t wchar_t FILE"
    private const val C_KEYWORDS = "auto break case const continue default do else enum extern for goto if inline register restrict return sizeof static struct switch typedef union volatile while _Bool _Atomic _Noreturn _Static_assert alignas alignof"

    val C = Grammar(keywords = C_KEYWORDS, types = C_TYPES, literals = "NULL true false", preprocessor = true, defines = "struct union enum")
    val CPP = Grammar(
        keywords = "$C_KEYWORDS class namespace template typename public private protected virtual override final friend operator new delete this throw try catch using constexpr consteval constinit decltype explicit mutable noexcept static_cast dynamic_cast reinterpret_cast const_cast co_await co_return co_yield concept requires export module import",
        types = "$C_TYPES string vector map set unordered_map unordered_set shared_ptr unique_ptr optional variant array auto",
        literals = "nullptr NULL true false",
        preprocessor = true,
        capitalTypes = true,
        quotes = listOf(Quote("R\"(", ")\"", null, true), Quote("\""), Quote("'", short = true)),
    )
    val JAVA = Grammar(
        keywords = "abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws transient try volatile while var record sealed permits non-sealed yield",
        types = "boolean byte char short int long float double void String Object Integer Long Boolean",
        literals = "true false null",
        annotations = true,
        capitalTypes = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("\""), Quote("'", short = true)),
    )
    val KOTLIN = Grammar(
        keywords = "as break class continue do else for fun if in interface is object package return super this throw try typealias typeof val var when while by catch constructor delegate dynamic field file finally get import init param property receiver set setparam where actual abstract annotation companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg value",
        types = "Int Long Short Byte Double Float Boolean Char String Unit Any Nothing List Map Set Array MutableList MutableMap",
        literals = "true false null",
        annotations = true,
        capitalTypes = true,
        quotes = listOf(Quote("\"\"\"", escape = null, multiline = true), Quote("\""), Quote("'", short = true)),
    )
    val SCALA = Grammar(
        keywords = "abstract case catch class def do else extends final finally for forSome if implicit import lazy match new object override package private protected return sealed super this throw trait try type val var while with yield given using enum then export",
        literals = "true false null",
        annotations = true,
        capitalTypes = true,
        quotes = listOf(Quote("\"\"\"", escape = null, multiline = true), Quote("\""), Quote("'", short = true)),
    )
    val GROOVY = Grammar(
        keywords = "abstract as assert break case catch class const continue def default do else enum extends final finally for goto if implements import in instanceof interface new package private protected public return static super switch synchronized this throw throws trait transient try var void volatile while",
        literals = "true false null",
        annotations = true,
        capitalTypes = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("'''", multiline = true), Quote("\""), Quote("'")),
    )
    val CSHARP = Grammar(
        keywords = "abstract as base break case catch checked class const continue default delegate do else enum event explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out override params private protected public readonly ref return sealed sizeof stackalloc static struct switch this throw try typeof unchecked unsafe using virtual volatile while async await var dynamic get set init record when where yield",
        types = "bool byte char decimal double float int long object sbyte short string uint ulong ushort void",
        literals = "true false null",
        preprocessor = true,
        capitalTypes = true,
        prefixes = "@ $",
    )
    val JAVASCRIPT = Grammar(
        keywords = "async await break case catch class const continue debugger default delete do else export extends finally for from function get if import in instanceof let new of return set static super switch this throw try typeof var void while with yield as",
        literals = "true false null undefined NaN Infinity",
        builtins = "console require module exports window document Math JSON Promise Object Array String Number Boolean Map Set Symbol Error",
        nameChars = "$",
        quotes = listOf(Quote("`", multiline = true), Quote("\""), Quote("'")),
        capitalTypes = true,
    )
    val TYPESCRIPT = Grammar(
        keywords = "async await break case catch class const continue debugger default delete do else enum export extends finally for from function get if implements import in instanceof interface let new of return set static super switch this throw try typeof var void while with yield as type namespace declare abstract private protected public readonly keyof infer is satisfies",
        types = "string number boolean any unknown never void object bigint symbol",
        literals = "true false null undefined NaN Infinity",
        nameChars = "$",
        annotations = true,
        quotes = listOf(Quote("`", multiline = true), Quote("\""), Quote("'")),
        capitalTypes = true,
    )
    val GO = Grammar(
        keywords = "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var",
        types = "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any comparable",
        literals = "true false nil iota",
        builtins = "append cap clear close complex copy delete imag len make max min new panic print println real recover",
        quotes = listOf(Quote("`", escape = null, multiline = true), Quote("\""), Quote("'", short = true)),
        capitalTypes = false,
    )
    val RUST = Grammar(
        keywords = "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait type unsafe use where while macro_rules",
        types = "bool char str u8 u16 u32 u64 u128 usize i8 i16 i32 i64 i128 isize f32 f64 String Vec Option Result Box",
        literals = "true false None Some Ok Err",
        capitalTypes = true,
        nested = true,
        quotes = listOf(Quote("\""), Quote("'", short = true)),
        prefixes = "b r br",
    )
    val SWIFT = Grammar(
        keywords = "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as catch is throw throws try async await some any self Self super",
        literals = "true false nil",
        annotations = true,
        capitalTypes = true,
        nested = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("\"")),
    )
    val DART = Grammar(
        keywords = "abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory final finally for get hide if implements import in interface is late library mixin new on operator part required rethrow return set show static super switch sync this throw try typedef var void while with yield",
        types = "int double num bool String List Map Set Future Stream",
        literals = "true false null",
        annotations = true,
        capitalTypes = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("'''", multiline = true), Quote("\""), Quote("'")),
    )
    val OBJC = Grammar(
        keywords = "$C_KEYWORDS @interface @implementation @end @property @synthesize @protocol @class @selector @autoreleasepool self super nil YES NO",
        types = "$C_TYPES id BOOL NSString NSArray NSDictionary NSInteger",
        literals = "nil YES NO NULL",
        preprocessor = true,
        capitalTypes = true,
    )
    val ZIG = Grammar(
        keywords = "addrspace align allowzero and anyframe anytype asm async await break callconv catch comptime const continue defer else enum errdefer error export extern fn for if inline linksection noalias noinline nosuspend opaque or orelse packed pub resume return struct suspend switch test threadlocal try union unreachable usingnamespace var volatile while",
        types = "bool void type anyerror u8 u16 u32 u64 usize i8 i16 i32 i64 isize f32 f64",
        literals = "true false null undefined",
        blockComments = emptyList(),
    )
    val PHP = Grammar(
        keywords = "abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield",
        literals = "true false null TRUE FALSE NULL",
        lineComments = listOf("//", "#"),
        variables = "$",
        ignoreCase = false,
        capitalTypes = true,
    )
    val PYTHON = Grammar(
        keywords = "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case type",
        types = "int float str bool list dict set tuple bytes object complex frozenset",
        literals = "True False None self cls",
        builtins = "print len range open input enumerate zip map filter sorted reversed isinstance super",
        lineComments = listOf("#"),
        blockComments = emptyList(),
        annotations = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("'''", multiline = true), Quote("\""), Quote("'")),
        prefixes = "r u b f rb br fr rf R U B F",
        capitalTypes = true,
    )
    val RUBY = Grammar(
        keywords = "BEGIN END alias and begin break case class def defined? do else elsif end ensure for if in module next not or redo rescue retry return self super then undef unless until when while yield require require_relative include extend attr_accessor attr_reader attr_writer private protected public raise lambda proc",
        literals = "true false nil",
        lineComments = listOf("#"),
        blockComments = listOf("=begin" to "=end"),
        variables = "@$",
        symbols = true,
        nameChars = "?!",
        capitalTypes = true,
    )
    val PERL = Grammar(
        keywords = "my our local sub if elsif else unless while until for foreach do last next redo return use no require package BEGIN END and or not eq ne lt gt le ge cmp qw q qq",
        builtins = "print printf open close die warn push pop shift unshift split join keys values defined exists delete chomp chop sprintf",
        lineComments = listOf("#"),
        blockComments = listOf("\n=" to "\n=cut"),
        variables = "$@%",
        wordComments = false,
    )
    val LUA = Grammar(
        keywords = "and break do else elseif end for function goto if in local not or repeat return then until while",
        literals = "true false nil",
        builtins = "print pairs ipairs require type tostring tonumber setmetatable getmetatable error pcall assert select",
        lineComments = listOf("--"),
        blockComments = listOf("--[[" to "]]"),
        quotes = listOf(Quote("[[", "]]", null, true), Quote("\""), Quote("'")),
    )
    val SHELL = Grammar(
        keywords = "if then else elif fi case esac for select while until do done in function time coproc return exit break continue local export readonly declare typeset unset source alias set shift trap eval exec",
        builtins = "echo printf read cd pwd test let getopts wait kill sudo grep sed awk find xargs cat curl wget tar chmod chown mkdir rm cp mv ln ls docker git kubectl ssh",
        literals = "true false",
        lineComments = listOf("#"),
        blockComments = emptyList(),
        wordComments = true,
        variables = "$",
        nameChars = "-",
        calls = false,
        quotes = listOf(Quote("\""), Quote("'", escape = null, multiline = true)),
        declares = "function",
        defines = "",
    )
    val DOCKERFILE = Grammar(
        keywords = "FROM RUN CMD LABEL MAINTAINER EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS if then else fi for do done in",
        lineComments = listOf("#"),
        blockComments = emptyList(),
        wordComments = true,
        variables = "$",
        nameChars = "-",
        calls = false,
        declares = "",
        defines = "",
    )
    val POWERSHELL = Grammar(
        keywords = "begin break catch class continue data define do dynamicparam else elseif end enum exit filter finally for foreach from function if in param process return switch throw trap try until using var while workflow",
        literals = "\$true \$false \$null",
        lineComments = listOf("#"),
        blockComments = listOf("<#" to "#>"),
        ignoreCase = true,
        variables = "$",
        nameChars = "-",
        calls = false,
        capitalTypes = false,
    )
    val BATCH = Grammar(
        keywords = "if else goto call exit set setlocal endlocal echo for in do not exist defined errorlevel shift pause start cd pushd popd del copy move mkdir rmdir type cls title choice",
        lineComments = listOf("::", "rem ", "@rem "),
        blockComments = emptyList(),
        ignoreCase = true,
        variables = "%",
        calls = false,
        quotes = listOf(Quote("\"")),
        declares = "",
        defines = "",
    )
    val R = Grammar(
        keywords = "if else repeat while function for in next break return library require",
        literals = "TRUE FALSE NULL NA NaN Inf T F",
        lineComments = listOf("#"),
        blockComments = emptyList(),
        nameChars = ".",
    )
    val JULIA = Grammar(
        keywords = "abstract baremodule begin break catch const continue do else elseif end export finally for function global if import let local macro module mutable primitive quote return struct try type using while where",
        literals = "true false nothing missing",
        lineComments = listOf("#"),
        blockComments = listOf("#=" to "=#"),
        capitalTypes = true,
        nested = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("\""), Quote("'", short = true)),
    )
    val ELIXIR = Grammar(
        keywords = "def defp defmodule defmacro defstruct defprotocol defimpl do end if else unless case cond with for fn when in import alias require use quote unquote receive try catch rescue after raise",
        literals = "true false nil",
        lineComments = listOf("#"),
        blockComments = emptyList(),
        annotations = true,
        symbols = true,
        nameChars = "?!",
        capitalTypes = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("\""), Quote("'")),
    )
    val ERLANG = Grammar(
        keywords = "after and andalso band begin bnot bor bsl bsr bxor case catch cond div end fun if let not of or orelse receive rem try when xor module export import record define",
        literals = "true false undefined ok error",
        lineComments = listOf("%"),
        blockComments = emptyList(),
        capitalTypes = true,
        quotes = listOf(Quote("\"")),
    )
    val HASKELL = Grammar(
        keywords = "case class data default deriving do else foreign if import in infix infixl infixr instance let module newtype of then type where qualified as hiding",
        literals = "True False Nothing Just",
        lineComments = listOf("--"),
        blockComments = listOf("{-" to "-}"),
        nested = true,
        nameChars = "'",
        capitalTypes = true,
        quotes = listOf(Quote("\""), Quote("'", short = true)),
    )
    val LISP = Grammar(
        keywords = "def defn defn- defmacro defun defvar defparameter defconst let let* if when unless cond case do loop fn lambda ns require import progn setq setf quote and or not",
        literals = "nil t true false",
        lineComments = listOf(";"),
        blockComments = listOf("#|" to "|#"),
        nameChars = "-!?*<>=/+.",
        symbols = true,
        calls = false,
        callAfterParen = true,
        quotes = listOf(Quote("\"", multiline = true)),
    )
    val SQL = Grammar(
        keywords = "select from where and or not in is null like between exists insert into values update set delete create alter drop table view index unique primary key foreign references constraint default join inner left right outer full cross on as group by order having limit offset union all distinct case when then else end begin commit rollback transaction grant revoke with returning if replace database schema column add trigger function procedure return returns language asc desc cascade",
        types = "int integer bigint smallint serial bigserial decimal numeric real float double precision varchar char text boolean bool date time timestamp timestamptz interval uuid json jsonb bytea blob",
        literals = "true false null",
        builtins = "count sum avg min max coalesce now cast lower upper length substring",
        lineComments = listOf("--", "#"),
        wordComments = true,
        ignoreCase = true,
        quotes = listOf(Quote("'", escape = null, multiline = true), Quote("\""), Quote("`")),
    )
    val GRAPHQL = Grammar(
        keywords = "query mutation subscription fragment on type interface union enum input scalar schema extend directive implements repeatable",
        types = "Int Float String Boolean ID",
        literals = "true false null",
        lineComments = listOf("#"),
        blockComments = emptyList(),
        variables = "$",
        annotations = true,
        capitalTypes = true,
        quotes = listOf(Quote("\"\"\"", multiline = true), Quote("\"")),
    )
    val PROTO = Grammar(
        keywords = "syntax edition package import option message enum service rpc returns oneof map repeated optional required reserved extend extensions to max stream public weak",
        types = "double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes",
        literals = "true false",
        capitalTypes = true,
    )
    val HCL = Grammar(
        keywords = "resource data variable output locals module provider terraform backend required_providers for in if dynamic content moved import check",
        literals = "true false null",
        lineComments = listOf("#", "//"),
        nameChars = "-",
        quotes = listOf(Quote("<<EOF", "EOF", null, true), Quote("\"")),
        declares = "",
        defines = "",
    )
    val NIX = Grammar(
        keywords = "let in with rec inherit if then else assert or import",
        literals = "true false null",
        builtins = "builtins pkgs lib mkDerivation fetchurl fetchFromGitHub",
        lineComments = listOf("#"),
        nameChars = "-'",
        calls = false,
        quotes = listOf(Quote("''", "''", null, true), Quote("\"")),
    )
    val AWK = Grammar(
        keywords = "BEGIN END if else while for in do break continue next nextfile exit return delete function getline print printf",
        builtins = "length substr index split sub gsub match sprintf tolower toupper system close",
        lineComments = listOf("#"),
        blockComments = emptyList(),
        variables = "$",
    )
    val VIM = Grammar(
        keywords = "if else elseif endif for endfor while endwhile function endfunction return let unlet set setlocal call execute map nmap vmap imap noremap nnoremap vnoremap inoremap autocmd augroup command syntax highlight source",
        lineComments = listOf("\""),
        blockComments = emptyList(),
        quotes = listOf(Quote("'", escape = null)),
        calls = true,
    )
    val TEX = Grammar(
        lineComments = listOf("%"),
        blockComments = emptyList(),
        variables = "\\",
        variableTone = Tone.KEYWORD,
        quotes = listOf(Quote("$", "$", null, true)),
        calls = false,
        declares = "",
        defines = "",
    )
    val ASM = Grammar(
        lineComments = listOf(";", "#", "//"),
        wordComments = true,
        nameChars = ".",
        calls = false,
        firstWord = Tone.KEYWORD,
        declares = "",
        defines = "",
    )
    val JSON = Grammar(literals = "true false null", keys = true, calls = false, declares = "", defines = "", quotes = listOf(Quote("\"")))
    val JSON5 = Grammar(literals = "true false null Infinity NaN", keys = true, calls = false, declares = "", defines = "")
    val CSS: Lexer = Css(lineComments = false)
    val SCSS: Lexer = Css(lineComments = true)
    val XML: Lexer = Markup(html = false)
    val HTML: Lexer = Markup(html = true)

    /** A language: the name it is shown by and how it is lexed. */
    class Language(val name: String, val lexer: Lexer)

    private val all: Map<String, Language> = buildMap {
        fun add(name: String, lexer: Lexer, vararg ids: String) {
            val language = Language(name, lexer)
            ids.forEach { put(it, language) }
        }
        add("C", C, "c", "h")
        add("C++", CPP, "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "ino", "cu")
        add("C#", CSHARP, "cs", "csx", "csharp")
        add("Java", JAVA, "java")
        add("Kotlin", KOTLIN, "kt", "kts", "kotlin")
        add("Scala", SCALA, "scala", "sc", "sbt")
        add("Groovy", GROOVY, "groovy", "gradle", "gvy", "jenkinsfile")
        add("JavaScript", JAVASCRIPT, "js", "mjs", "cjs", "jsx", "javascript")
        add("TypeScript", TYPESCRIPT, "ts", "mts", "cts", "tsx", "typescript")
        add("Go", GO, "go", "golang")
        add("Rust", RUST, "rs", "rust")
        add("Swift", SWIFT, "swift")
        add("Dart", DART, "dart")
        add("Objective-C", OBJC, "m", "mm", "objc")
        add("Zig", ZIG, "zig")
        add("PHP", PHP, "php", "phtml")
        add("Python", PYTHON, "py", "pyi", "pyw", "python", "gyp", "bzl", "star")
        add("Ruby", RUBY, "rb", "rake", "gemspec", "ruby", "podspec")
        add("Perl", PERL, "pl", "pm", "perl", "t")
        add("Lua", LUA, "lua")
        add("Shell", SHELL, "sh", "bash", "zsh", "ksh", "fish", "shell", "command", "bats", "ebuild")
        add("PowerShell", POWERSHELL, "ps1", "psm1", "psd1", "powershell")
        add("Batch", BATCH, "bat", "cmd", "batch")
        add("R", R, "r")
        add("Julia", JULIA, "jl", "julia")
        add("Elixir", ELIXIR, "ex", "exs", "elixir")
        add("Erlang", ERLANG, "erl", "hrl", "erlang")
        add("Haskell", HASKELL, "hs", "lhs", "haskell")
        add("Lisp", LISP, "clj", "cljs", "cljc", "edn", "lisp", "lsp", "el", "scm", "rkt", "fnl", "clojure")
        add("SQL", SQL, "sql", "psql", "mysql", "pgsql")
        add("GraphQL", GRAPHQL, "graphql", "gql")
        add("Protocol Buffers", PROTO, "proto", "protobuf")
        add("HCL", HCL, "tf", "tfvars", "hcl", "nomad", "terraform")
        add("Nix", NIX, "nix")
        add("AWK", AWK, "awk")
        add("Vim script", VIM, "vim", "vimrc")
        add("TeX", TEX, "tex", "sty", "cls", "bib", "latex")
        add("Assembly", ASM, "asm", "s", "nasm")
        add("JSON", JSON, "json", "geojson", "jsonl", "ndjson", "webmanifest", "har", "ipynb")
        add("JSON5", JSON5, "jsonc", "json5")
        add("YAML", Yaml, "yaml", "yml", "yaml-tmlanguage")
        add("TOML", Pairs(comments = "#", toml = true), "toml")
        add("INI", Pairs(), "ini", "cfg", "inf", "service", "timer", "socket", "mount", "automount", "path", "target", "slice", "network", "netdev", "link", "desktop", "editorconfig", "gitconfig", "gitmodules", "npmrc", "pylintrc", "flake8")
        add("Properties", Pairs(comments = "#!"), "properties", "env", "lang")
        add("Configuration", Conf, "conf", "config", "nginx", "caddyfile", "rules", "sshd", "tmux")
        add("XML", XML, "xml", "plist", "xsd", "xsl", "xslt", "svg", "kml", "gpx", "rss", "atom", "pom", "csproj", "resx", "storyboard", "xib", "wsdl", "xaml", "iml", "fxml")
        add("HTML", HTML, "html", "htm", "xhtml", "vue", "svelte", "astro", "jsp", "erb", "hbs", "mustache", "twig", "njk")
        add("CSS", CSS, "css")
        add("SCSS", SCSS, "scss", "sass", "less", "styl")
        add("Diff", Diff, "diff", "patch", "rej")
        add("Log", Log, "log", "out", "srt", "vtt", "ass")
        add("Markdown", MarkdownSource, "md", "markdown", "mdown", "mkd", "mdx", "rmd")
        add("Makefile", Makefile, "mk", "mak", "make", "makefile")
        add("Dockerfile", DOCKERFILE, "dockerfile", "containerfile", "docker")
        add("RouterOS", RouterOs, "rsc", "routeros")
        add("Text", Plain, "txt", "text", "csv", "tsv", "asc", "nfo", "lst", "readme", "license", "authors", "changelog", "todo", "me", "1st")
    }

    // Files known by their whole name, with no extension or an unhelpful one.
    private val byName: Map<String, String> = mapOf(
        "dockerfile" to "dockerfile", "containerfile" to "containerfile", "makefile" to "makefile", "gnumakefile" to "makefile",
        "jenkinsfile" to "groovy", "vagrantfile" to "rb", "gemfile" to "rb", "rakefile" to "rb", "podfile" to "rb", "brewfile" to "rb",
        "caddyfile" to "caddyfile", "cmakelists.txt" to "cmake", ".bashrc" to "sh", ".zshrc" to "sh", ".profile" to "sh", ".bash_profile" to "sh",
        ".bash_aliases" to "sh", ".zprofile" to "sh", ".env" to "env", ".gitignore" to "txt", ".gitattributes" to "txt", ".dockerignore" to "txt",
        ".editorconfig" to "editorconfig", ".gitconfig" to "gitconfig", ".npmrc" to "npmrc", ".vimrc" to "vim", "crontab" to "sh",
        "nginx.conf" to "nginx", "sshd_config" to "sshd", "ssh_config" to "sshd", "hosts" to "conf", "fstab" to "conf", "license" to "txt",
        "readme" to "txt", "authors" to "txt", "copying" to "txt", "notice" to "txt", "changelog" to "txt", "go.mod" to "go", "go.sum" to "txt",
        "build" to "bzl", "workspace" to "bzl", "procfile" to "yaml", ".tmux.conf" to "tmux", ".prettierrc" to "json", ".eslintrc" to "json",
        ".babelrc" to "json", "flake.lock" to "json", "package.json" to "json", "cargo.lock" to "toml", "pipfile" to "toml",
    )

    /** The language of a file called [name]; null when the name does not tell. */
    fun of(name: String): Language? {
        val lower = name.lowercase()
        byName[lower]?.let { return all[it] ?: all["txt"] }
        if (lower.startsWith(".env.")) return all["env"]
        if (lower.startsWith("dockerfile.") || lower.endsWith(".dockerfile")) return all["dockerfile"]
        return all[Paths.extension(name)]
    }

    /** The language a script names in its first line, `#!/usr/bin/env python3`. */
    fun ofScript(firstLine: String): Language? {
        if (!firstLine.startsWith("#!")) return null
        val command = firstLine.removePrefix("#!").trim().split(' ', '/').filter { it.isNotEmpty() && it != "env" && !it.startsWith("-") }.lastOrNull().orEmpty()
        return when {
            command.startsWith("python") -> all["py"]
            command in setOf("sh", "bash", "zsh", "ksh", "dash", "ash", "fish") -> all["sh"]
            command.startsWith("node") || command == "deno" || command == "bun" -> all["js"]
            command.startsWith("perl") -> all["pl"]
            command.startsWith("ruby") -> all["rb"]
            command.startsWith("php") -> all["php"]
            command.startsWith("lua") -> all["lua"]
            command == "awk" || command == "gawk" -> all["awk"]
            else -> all["sh"]
        }
    }

    /** A language as a fence of Markdown names it: `kotlin`, `py`, `shell`. */
    fun byName(name: String): Lexer? = all[name.lowercase()]?.lexer ?: when (name.lowercase()) {
        "console", "zsh", "terminal" -> SHELL
        "yml" -> Yaml
        else -> null
    }
}
