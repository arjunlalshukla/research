use std::process::Command;
use std::str;
use regex::Regex;
use std::thread::sleep;
use std::time::Duration;
use std::env;

fn main() {
    let args = env::args().collect::<Vec<String>>();
    if args.len() == 2 {
        let interval = match args[1].parse::<u16>() {
            Ok(i) => { i },
            Err(_) => {
                println!("Argument could not be parsed as unsigned 16-bit");
                return;
            }
        };
        loop {
            println!("{}", multithreaded_frac());
            sleep(Duration::from_secs(interval as u64));
        }
    } else {
        println!("Usage: {} <interval>", args[0]);
    }
}

fn multithreaded_frac() -> f64 {
    let ws = Regex::new(r"\s+").unwrap();

    let psout = Command::new("ps")
        .arg("augx")
        .output()
        .expect("Failed to start ps")
        .stdout;

    let psout: Vec<&str> = str::from_utf8(&psout).unwrap()
        .split('\n')
        .skip(1)
        .map(|line| ws.splitn(line, 9).take(8).collect::<Vec<&str>>())
        .filter(|columns| columns.len() == 8)
        .map(|columns| columns[7])
        .collect();

    psout.iter().filter(|s| s.contains("s")).count() as f64 / psout.len() as f64
}