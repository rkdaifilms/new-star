import argparse
import json
import os
from pathlib import Path

import torch
from datasets import load_dataset
from peft import LoraConfig
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from trl import SFTConfig, SFTTrainer

DEFAULT_MODEL = "Qwen/Qwen2.5-3B-Instruct"


def main():
    parser = argparse.ArgumentParser(description="QLoRA fine-tune a private New Star model adapter")
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--base-model", default=DEFAULT_MODEL)
    parser.add_argument("--epochs", type=float, default=2.0)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--gradient-accumulation", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=0.0001)
    parser.add_argument("--max-seq-length", type=int, default=2048)
    args = parser.parse_args()
    if not torch.cuda.is_available():
        raise SystemExit("CUDA GPU is required; CPU fine-tuning is intentionally disabled")
    if not 1 <= args.batch_size <= 4 or not 1 <= args.gradient_accumulation <= 128:
        raise SystemExit("batch-size must be 1-4 and gradient-accumulation 1-128")
    if not 1 <= args.epochs <= 5 or not 512 <= args.max_seq_length <= 8192:
        raise SystemExit("epochs must be 1-5 and max-seq-length 512-8192")
    if not 1e-6 <= args.learning_rate <= 5e-4:
        raise SystemExit("learning-rate must be between 1e-6 and 5e-4")
    dataset_dir = Path(args.dataset_dir)
    train_file, validation_file = dataset_dir / "train.jsonl", dataset_dir / "validation.jsonl"
    if not train_file.is_file() or not validation_file.is_file():
        raise SystemExit("Run prepare_dataset.py first to create train.jsonl and validation.jsonl")
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(args.base_model, use_fast=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    def format_record(example):
        text = tokenizer.apply_chat_template(example["messages"], tokenize=False, add_generation_prompt=False)
        return {"text": text}
    dataset = load_dataset("json", data_files={"train": str(train_file), "validation": str(validation_file)})
    dataset = dataset.map(format_record, remove_columns=dataset["train"].column_names)
    quantization = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16,
        bnb_4bit_use_double_quant=True,
    )
    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        quantization_config=quantization,
        device_map="auto",
        torch_dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16,
        use_cache=False,
    )
    model.config.use_cache = False
    lora = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
    training_args = SFTConfig(
        output_dir=str(output),
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        per_device_eval_batch_size=1,
        gradient_accumulation_steps=args.gradient_accumulation,
        learning_rate=args.learning_rate,
        logging_steps=10,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        max_seq_length=args.max_seq_length,
        dataset_text_field="text",
        packing=False,
        gradient_checkpointing=True,
        optim="paged_adamw_8bit",
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        bf16=torch.cuda.is_bf16_supported(),
        fp16=not torch.cuda.is_bf16_supported(),
        report_to=[],
        seed=42,
    )
    trainer = SFTTrainer(
        model=model,
        args=training_args,
        train_dataset=dataset["train"],
        eval_dataset=dataset["validation"],
        processing_class=tokenizer,
        peft_config=lora,
    )
    trainer.train()
    trainer.save_model(str(output / "adapter"))
    tokenizer.save_pretrained(str(output / "adapter"))
    report = {
        "base_model": args.base_model,
        "adapter": "adapter",
        "epochs": args.epochs,
        "max_seq_length": args.max_seq_length,
        "training_rows": len(dataset["train"]),
        "validation_rows": len(dataset["validation"]),
        "final_metrics": trainer.state.log_history[-1] if trainer.state.log_history else {},
        "training_device": torch.cuda.get_device_name(0),
        "note": "QLoRA adapter only. This is fine-tuning of the selected open-weight base model, not pretraining a New Star foundation model from random initialization.",
    }
    (output / "training-report.json").write_text(json.dumps(report, indent=2, default=str), encoding="utf-8")
    print(json.dumps(report, indent=2, default=str))


if __name__ == "__main__":
    main()
