"""Create the isolated MySQL database used by OrderRefundWorkflowTest."""
print("SET NAMES utf8mb4;")
print("CREATE DATABASE IF NOT EXISTS refund_workflow_qa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
