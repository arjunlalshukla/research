use bounded_vec_deque::BoundedVecDeque;
use lazy_static::lazy_static;
use shell_words::split;
use std::collections::HashMap;
use std::env;
use std::io::{Write, stdin, stdout};
use std::path::Path;
use std::process::Command;

const HISTORY_SIZE: usize = 127;

lazy_static! {
    static ref BUILT_INS: HashMap<&'static str, fn(Vec<String>) -> ()> = {
        let mut m: HashMap<&'static str, fn(Vec<String>) -> ()> = HashMap::new();
        m.insert("cd", cd);
        m.insert("exit", exit);
        m.insert("jobs", jobs);
        m
    };
}

fn main() {
    let prompt = env::var("DASH_PTOMPT").unwrap_or("my_dash> ".to_string());

    let mut history: BoundedVecDeque<String> = BoundedVecDeque::new(HISTORY_SIZE);

    loop {
        print!("{}", prompt);
        stdout().flush().unwrap();
        let mut s = String::new();
        match stdin().read_line(& mut s) {
            Ok(0) => exit(Vec::new()),
            Ok(_) => {
                s.pop();
                history.push_back(s);
                execute(history.back().unwrap());
            }
            Err(_) => {}
        };
    }
}

fn execute(line: &String) {
    match split(&line[..]) {
        Ok(mut v) => if !v.is_empty() {
            let cmd = v.remove(0);
            match BUILT_INS.get(&cmd[..]) {
                Some(func) => func(v),
                None => match Command::new(cmd).args(v).spawn() {
                    Ok(mut p) => match p.wait() {
                        Ok(_) => {}
                        Err(e) => println!("blah could not wait for process: {}", e)
                    },
                    Err(e) => println!("boob could not start process: {}", e)
                }
            }
        },
        Err(e) => println!("error: {}", e)
    }
}

fn cd(args: Vec<String>) {
    if args.len() == 1 {
        let dir = 
            if args[0].chars().count() == 0 {
                match std::env::home_dir() {
                    Some(s) => match s.to_str() {
                        Some(s) => s.to_string(),
                        None => {
                            println!("home directory path is not valid Unicode");
                            return;
                        }
                    },
                    None => {
                        println!("cannot find home directory!");
                        return;
                    }
                }
            } else {
                String::new()
            };

        match std::env::set_current_dir(Path::new(&args[0])) {
            Ok(_) => {}
            Err(e) => println!("error: {}", e)
        };
    } else {
        println!("cd takes exactly 1 argument")
    }
}

fn exit(args: Vec<String>) {
    if args.len() == 0 {
        println!("bye");
        std::process::exit(0);
    } else {
        println!("exit takes exactly 0 arguments")
    }
}

fn jobs(args: Vec<String>) {
    if args.len() == 0 {

    } else {
        println!("jobs takes exactly 0 arguments")
    }
}
