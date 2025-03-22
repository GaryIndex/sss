import requests
import json
from web3 import Web3
from solana.rpc.api import Client as SolanaClient
from datetime import datetime, timedelta
from slither import Slither
import os
import matplotlib.pyplot as plt  # 可视化支持

# 配置
XAI_API_KEY = "your_xai_api_key"  # 替换为你的xAI API密钥
XAI_API_URL = "https://api.x.ai/v1/chat/completions"
RPC_URLS = {
    "ETH": "https://mainnet.infura.io/v3/your_infura_key",  # Ethereum Mainnet
    "BSC": "https://bsc-dataseed.binance.org/",             # Binance Smart Chain
    "SOL": "https://api.mainnet-beta.solana.com",          # Solana Mainnet
    "POLY": "https://polygon-rpc.com/",                    # Polygon (MATIC)
    "AVAX": "https://api.avax.network/ext/bc/C/rpc",       # Avalanche C-Chain
    "ARB": "https://arb1.arbitrum.io/rpc",                 # Arbitrum One
    "OP": "https://mainnet.optimism.io",                   # Optimism
    "FTM": "https://rpc.ftm.tools/",                       # Fantom
    "TRON": "https://api.trongrid.io",                     # TRON
    "ADA": "https://cardano-mainnet.blockfrost.io",        # Cardano (requires API key)
    "XRP": "https://xrplcluster.com",                      # XRP Ledger
    "DOT": "wss://rpc.polkadot.io",                        # Polkadot (WebSocket)
    "KSM": "wss://kusama-rpc.polkadot.io",                 # Kusama (WebSocket)
    "ALGO": "https://mainnet-algorand.api.purestake.io/ps2",# Algorand (may need API key)
    "NEAR": "https://rpc.mainnet.near.org",                # NEAR Protocol
    "COSMOS": "https://rpc.cosmos.network",                # Cosmos Hub
    "STELLAR": "https://horizon.stellar.org",              # Stellar
    "HARMONY": "https://api.harmony.one",                  # Harmony
    "HECO": "https://http-mainnet.hecochain.com",          # Huobi ECO Chain
    "KLAY": "https://public-node-api.klaytnapi.com/v1/cypress", # Klaytn
    "MOVR": "https://rpc.moonriver.moonbeam.network",      # Moonriver
    "CELO": "https://forno.celo.org",                      # Celo
    "RON": "https://api.roninchain.com/rpc",               # Ronin (Axie Infinity)
    "GNO": "https://rpc.gnosischain.com",                  # Gnosis Chain
    "THETA": "https://eth-rpc-api.thetatoken.org/rpc",     # Theta Network
}
BLACKLIST_ADDRESSES = {"0xBlacklistedAddress"}  # 示例黑名单，需集成实时数据
TOP_5_AUDITORS = ["Quantstamp", "CertiK", "OpenZeppelin", "Hacken", "Consensys Diligence"]
AUDIT_REPORT_URLS = {
    "Quantstamp": "https://quantstamp.com/audits",
    "CertiK": "https://www.certik.com",
    "OpenZeppelin": "https://www.openzeppelin.com/security-audits",
    "Hacken": "https://hacken.io/audits",
    "Consensys Diligence": "https://diligence.consensys.io/audits"
}

# ERC-20 ABI
TOKEN_ABI = [
    {"constant": True, "inputs": [{"name": "_owner", "type": "address"}],
     "name": "balanceOf", "outputs": [{"name": "balance", "type": "uint256"}],
     "type": "function"},
    {"constant": True, "inputs": [], "name": "totalSupply",
     "outputs": [{"name": "", "type": "uint256"}], "type": "function"},
    {"anonymous": False, "inputs": [
        {"indexed": True, "name": "from", "type": "address"},
        {"indexed": True, "name": "to", "type": "address"},
        {"indexed": False, "name": "value", "type": "uint256"}],
     "name": "Transfer", "type": "event"}
]

# 识别公链
def detect_blockchain(contract_input):
    if isinstance(contract_input, str):
        if "pragma solidity" in contract_input.lower():
            return "Ethereum (ETH)"
        elif "use solana_program" in contract_input.lower():
            return "Solana (SOL)"
        
        for chain, rpc in RPC_URLS.items():
            try:
                if chain == "SOL":
                    client = SolanaClient(rpc)
                    if client.get_account_info(contract_input).value is not None:
                        return "Solana (SOL)"
                else:
                    w3 = Web3(Web3.HTTPProvider(rpc))
                    if w3.is_address(contract_input) and w3.eth.get_code(contract_input) != "0x":
                        return chain
            except:
                continue
    return "未知公链"

# 获取创建信息
def get_creation_info(contract_address, chain):
    if chain == "Solana (SOL)":
        client = SolanaClient(RPC_URLS["SOL"])
        account_info = client.get_account_info(contract_address).value
        if not account_info:
            return None, None, None
        signatures = client.get_signatures_for_address(contract_address, limit=1).value
        if signatures:
            tx = client.get_transaction(signatures[0].signature).value
            creator = str(tx.transaction.message.account_keys[0])
            creation_time = datetime.utcfromtimestamp(tx.block_time)
            return creator, creation_time, tx.slot
    else:
        w3 = Web3(Web3.HTTPProvider(RPC_URLS[chain]))
        latest_block = w3.eth.block_number
        tx_receipt = None
        for block in range(0, latest_block, 1000):
            txs = w3.eth.get_block(block, full_transactions=True)["transactions"]
            for tx in txs:
                if tx["to"] is None and w3.to_checksum_address(tx["contractAddress"]) == contract_address:
                    tx_receipt = w3.eth.get_transaction_receipt(tx["hash"])
                    break
            if tx_receipt:
                break
        if tx_receipt:
            block = w3.eth.get_block(tx_receipt["blockNumber"])
            return tx_receipt["from"], datetime.utcfromtimestamp(block["timestamp"]), tx_receipt["blockNumber"]
    return None, None, None

# 代码安全审计（Solidity）
def audit_code_security(contract_code):
    if "pragma solidity" not in contract_code:
        return []
    with open("temp.sol", "w") as f:
        f.write(contract_code)
    slither = Slither("temp.sol")
    vulnerabilities = []
    
    for contract in slither.contracts:
        for function in contract.functions:
            # 重入攻击
            if function.is_public and "call" in str(function) and "reentrancy" not in str(function.modifiers):
                vulnerabilities.append({"type": "重入攻击风险", "function": function.name})
            # 整数溢出
            if slither.solc_version < "0.8.0" and ("add" in str(function) or "sub" in str(function)):
                vulnerabilities.append({"type": "整数溢出/下溢风险", "function": function.name})
            # 权限控制
            if function.is_public and "onlyOwner" not in [m.name for m in function.modifiers]:
                vulnerabilities.append({"type": "权限控制问题", "function": function.name})
            # 时间戳依赖
            if "block.timestamp" in str(function):
                vulnerabilities.append({"type": "时间戳依赖风险", "function": function.name})
            # 伪随机数
            if "blockhash" in str(function) or "block.number" in str(function):
                vulnerabilities.append({"type": "伪随机数风险", "function": function.name})
        # 锁仓机制检查
        if not any("lock" in f.name or "timelock" in f.name for f in contract.functions):
            vulnerabilities.append({"type": "无锁仓机制", "description": "未发现代币锁仓逻辑"})
    os.remove("temp.sol")
    return vulnerabilities

# 老鼠仓检测（改进）
def detect_rat_warehouse(contract_address, chain, start_block_or_slot, creation_time):
    if chain == "Solana (SOL)":
        client = SolanaClient(RPC_URLS["SOL"])
        signatures = client.get_signatures_for_address(contract_address, limit=1000).value
        transfers = []
        holders = {}
        creator_transfers = []
        end_time_24h = creation_time + timedelta(hours=24)
        for sig in signatures:
            tx = client.get_transaction(sig.signature).value
            if tx and "amount" in tx.transaction.message.instructions[0]:
                instr = tx.transaction.message.instructions[0]
                block_time = datetime.utcfromtimestamp(tx.block_time)
                from_addr = str(instr.accounts[1])
                to_addr = str(instr.accounts[0])
                value = instr["amount"] / 10**6
                transfers.append({"from": from_addr, "to": to_addr, "value": value, "time": block_time})
                if block_time <= end_time_24h:
                    holders[to_addr] = holders.get(to_addr, 0) + value
                if from_addr == "11111111111111111111111111111111":
                    creator_transfers.append({"to": to_addr, "value": value, "time": block_time})
    else:
        w3 = Web3(Web3.HTTPProvider(RPC_URLS[chain]))
        contract = w3.eth.contract(address=contract_address, abi=TOKEN_ABI)
        total_supply = contract.functions.totalSupply().call() / 10**18
        transfers = contract.events.Transfer().get_logs(fromBlock=start_block_or_slot, toBlock="latest")[:1000]
        holders = {}
        creator_transfers = []
        end_block_24h = start_block_or_slot + 100  # 假设24小时约100块
        for event in transfers:
            block_time = datetime.utcfromtimestamp(w3.eth.get_block(event["blockNumber"])["timestamp"])
            from_addr = event["args"]["from"]
            to_addr = event["args"]["to"]
            value = event["args"]["value"] / 10**18
            transfers.append({"from": from_addr, "to": to_addr, "value": value, "time": block_time})
            if event["blockNumber"] <= end_block_24h:
                holders[to_addr] = holders.get(to_addr, 0) + value
            if from_addr == "0x0000000000000000000000000000000000000000":
                creator_transfers.append({"to": to_addr, "value": value, "time": block_time})
    
    total_transferred = sum(t["value"] for t in transfers)
    top_holders = sorted(holders.items(), key=lambda x: x[1], reverse=True)[:5]
    concentration = sum(amount for _, amount in top_holders) / total_transferred * 100 if total_transferred else 0
    
    # 抛售检测（7天内）
    dumps = {}
    for t in transfers:
        if t["time"] < creation_time + timedelta(days=7):
            from_addr = t["from"]
            value = t["value"]
            if from_addr in holders and value > holders[from_addr] * 0.5:
                dumps[from_addr] = dumps.get(from_addr, 0) + value
    
    # 关联地址分析
    related_addresses = {}
    for t in creator_transfers:
        to_addr = t["to"]
        if chain == "Solana (SOL)":
            signatures = client.get_signatures_for_address(to_addr, limit=1).value
            if signatures:
                first_tx_time = datetime.utcfromtimestamp(client.get_transaction(signatures[0].signature).value.block_time)
                related_addresses[to_addr] = {"amount": t["value"], "first_use": first_tx_time}
    
    suspicious = concentration > 50 or any(t["value"] > total_transferred * 0.05 for t in creator_transfers)
    return {
        "total_transferred": total_transferred,
        "concentration_24h": concentration,
        "top_holders": dict(top_holders),
        "creator_transfers": creator_transfers,
        "dumps_7d": dumps,
        "related_addresses": related_addresses,
        "suspicious": suspicious
    }

# 洗钱检测（改进）
def detect_money_laundering(contract_address, chain, start_block_or_slot):
    suspicious_activity = []
    if chain == "Solana (SOL)":
        client = SolanaClient(RPC_URLS["SOL"])
        signatures = client.get_signatures_for_address(contract_address, limit=1000).value
        transfers = []
        tx_times = []
        for sig in signatures:
            tx = client.get_transaction(sig.signature).value
            if tx and "amount" in tx.transaction.message.instructions[0]:
                instr = tx.transaction.message.instructions[0]
                value = instr["amount"] / 10**6
                tx_time = datetime.utcfromtimestamp(tx.block_time)
                transfers.append({"value": value, "time": tx_time})
                if value > 100000:
                    suspicious_activity.append({"type": "大额转账", "value": value})
                tx_times.append(tx_time)
        # 高频交易
        if len(tx_times) > 20:
            time_diffs = [(tx_times[i] - tx_times[i-1]).total_seconds() for i in range(1, len(tx_times))]
            if min(time_diffs) < 60:
                suspicious_activity.append({"type": "高频交易", "count": len(tx_times)})
    else:
        w3 = Web3(Web3.HTTPProvider(RPC_URLS[chain]))
        contract = w3.eth.contract(address=contract_address, abi=TOKEN_ABI)
        total_supply = contract.functions.totalSupply().call() / 10**18
        transfers = contract.events.Transfer().get_logs(fromBlock=start_block_or_slot, toBlock="latest")[:1000]
        tx_times = []
        for event in transfers:
            value = event["args"]["value"] / 10**18
            tx_time = datetime.utcfromtimestamp(w3.eth.get_block(event["blockNumber"])["timestamp"])
            if value > total_supply * 0.1:
                suspicious_activity.append({"type": "大额转账", "value": value})
            if event["args"]["from"] in BLACKLIST_ADDRESSES or event["args"]["to"] in BLACKLIST_ADDRESSES:
                suspicious_activity.append({"type": "黑名单地址", "tx": event["transactionHash"].hex()})
            tx_times.append(tx_time)
        if len(tx_times) > 20:
            time_diffs = [(tx_times[i] - tx_times[i-1]).total_seconds() for i in range(1, len(tx_times))]
            if min(time_diffs) < 60:
                suspicious_activity.append({"type": "高频交易", "count": len(tx_times)})
    
    # 混币器和跨链桥（模拟）
    if chain == "Solana (SOL)":
        for t in transfers:
            if "mixer" in str(t):  # 需真实混币器地址
                suspicious_activity.append({"type": "混币器使用", "value": t["value"]})
    return {"suspicious_activity": suspicious_activity}

# Rug Pull检测
def detect_rug_pull(contract_address, chain, creator, transfers):
    rug_pull_indicators = []
    if chain == "Solana (SOL)":
        client = SolanaClient(RPC_URLS["SOL"])
        creator_balance = client.get_balance(creator).value / 10**9  # SOL余额
        if creator_balance < 1:  # 假设清空余额
            rug_pull_indicators.append({"type": "创建者清空余额", "balance": creator_balance})
    else:
        w3 = Web3(Web3.HTTPProvider(RPC_URLS[chain]))
        creator_balance = w3.eth.get_balance(creator) / 10**18  # ETH余额
        if creator_balance < 0.01:
            rug_pull_indicators.append({"type": "创建者清空余额", "balance": creator_balance})
    
    # 流动性移除（简化）
    for t in transfers:
        if t["from"] == creator and t["value"] > sum(t["value"] for t in transfers) * 0.5:
            rug_pull_indicators.append({"type": "大额撤资", "value": t["value"]})
    return rug_pull_indicators

# 合规性检测
def detect_compliance_issues(rat_data, laundering_data):
    issues = []
    if rat_data["concentration_24h"] > 70:
        issues.append("24小时内代币分发过于集中，可能违反公平性原则")
    if laundering_data["suspicious_activity"]:
        issues.append("涉及可疑交易（如大额转账或高频交易）")
    return issues

# 检查Top 5审计状态（改进）
def check_top5_audit_status(contract_address, chain):
    audit_status = {}
    for auditor in TOP_5_AUDITORS:
        audit_status[auditor] = {
            "audited": False,  # 需API或手动验证
            "report_url": f"{AUDIT_REPORT_URLS[auditor]}/project-name"  # 示例URL
        }
    # 模拟CertiK API检查（需真实API）
    return audit_status

# 获取完整审计数据
def fetch_audit_data(contract_input, contract_address=None):
    chain = detect_blockchain(contract_input)
    if chain == "未知公链":
        return {"chain": chain, "error": "无法识别公链"}
    
    contract_address = contract_address or (contract_input if not isinstance(contract_input, str) or "pragma" not in contract_input else "0xYourContractAddress")
    creator, creation_time, start_block_or_slot = get_creation_info(contract_address, chain)
    if not creator:
        return {"chain": chain, "error": "无法找到创建信息"}
    
    code_vulnerabilities = audit_code_security(contract_input) if isinstance(contract_input, str) and "pragma solidity" in contract_input else []
    rat_data = detect_rat_warehouse(contract_address, chain, start_block_or_slot, creation_time)
    laundering_data = detect_money_laundering(contract_address, chain, start_block_or_slot)
    rug_pull_data = detect_rug_pull(contract_address, chain, creator, [t for t in rat_data["creator_transfers"]])
    compliance_issues = detect_compliance_issues(rat_data, laundering_data)
    audit_status = check_top5_audit_status(contract_address, chain)
    
    return {
        "chain": chain,
        "contract_address": contract_address,
        "creation_time": creation_time.strftime('%Y-%m-%d %H:%M:%S UTC') if creation_time else "未知",
        "creator": creator,
        "code_vulnerabilities": code_vulnerabilities,
        "rat_warehouse": rat_data,
        "money_laundering": laundering_data,
        "rug_pull": rug_pull_data,
        "compliance_issues": compliance_issues,
        "top5_audit_status": audit_status
    }

# 生成可视化（持有者分布）
def generate_visualization(audit_data):
    if audit_data["rat_warehouse"]["top_holders"]:
        holders = audit_data["rat_warehouse"]["top_holders"]
        plt.bar(list(holders.keys())[:5], list(holders.values())[:5])
        plt.title("Top 5 Holders Distribution")
        plt.xlabel("Holder Address")
        plt.ylabel("Amount")
        plt.savefig("holders_distribution.png")
        plt.close()

# 生成审计报告
def generate_audit_report(audit_data):
    generate_visualization(audit_data)
    headers = {"Authorization": f"Bearer {XAI_API_KEY}", "Content-Type": "application/json"}
    prompt = f"""
    请生成智能合约审计报告，基于以下数据：
    {json.dumps(audit_data, indent=2, default=str)}
    包括公链类型、代码安全、老鼠仓、洗钱风险、Rug Pull风险、合规性问题及Top 5审计状态。
    提供详细分析、风险评估和建议（按高、中、低风险分级）。
    """
    payload = {"model": "grok", "messages": [{"role": "user", "content": prompt}], "max_tokens": 4000}
    
    response = requests.post(XAI_API_URL, headers=headers, json=payload)
    return response.json()["choices"][0]["message"]["content"] if response.status_code == 200 else "API调用失败"

# 主函数
def run_full_audit(contract_input, contract_address=None):
    print("开始完整智能合约审计...")
    audit_data = fetch_audit_data(contract_input, contract_address)
    print("审计数据：", json.dumps(audit_data, indent=2, default=str))
    
    report = generate_audit_report(audit_data)
    print("\n完整审计报告：")
    print(report)

if __name__ == "__main__":
    # 示例：Solana地址
    solana_address = "FmFnRZWRLnZMcRDQGDCoHSdgLUFMZ7bAtD2qk75Vpump"
    run_full_audit(solana_address)
    
    
    #依赖：pip install requests web3.py pysolana slither-analyzer matplotlib