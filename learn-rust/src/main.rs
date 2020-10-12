use std::process::Command;
use std::str;
use regex::Regex;
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let ws= Regex::new(r"\s+").unwrap();

    loop {
        let psout = Command::new("ps")
            .arg("augx")
            .output()
            .expect("Failed to start ps")
            .stdout;

        let psout: Vec<&str> = str::from_utf8(&psout)
            .unwrap()
            .split('\n')
            .skip(1)
            .map(|line| ws.splitn(line, 9).take(8).collect::<Vec<&str>>())
            .filter(|columns| columns.len() == 8)
            .map(|columns| columns[7])
            .collect();

        let prop = psout.iter()
            .filter(|s| s.contains("s"))
            .collect::<Vec<&&str>>()
            .len() as f64 /
            psout.len() as f64;

        println!("{}", prop);

        sleep(Duration::from_secs(1));
    }
}