import time
import requests

# Replace with your actual token and image ID
token = ''
image_id = ''
region = ''
instance_type = ''
root_pass = ''

headers = {
    'Authorization': f'Bearer {token}',
    'Content-Type': 'application/json',
}

# Create the Linode instance without booting
create_instance_data = {
    'region': region,
    'type': instance_type,
    'image': image_id,
    'root_pass': root_pass,
    'label': 'my-custom-linode',
    'authorized_keys': ['ssh-rsa AAA...'],
    'booted': False,  # Ensure the instance is not booted
}

response = requests.post('https://api.linode.com/v4/linode/instances', headers=headers, json=create_instance_data)
instance = response.json()

# Get the instance ID
linode_id = instance['id']

print(f"Linode VM created with ID: {linode_id}. Waiting for 1 seconds before confuguration update...")
time.sleep(1)

# Get the configuration profile ID
instance_configs = requests.get(f'https://api.linode.com/v4/linode/instances/{linode_id}/configs', headers=headers).json()
config_id = instance_configs['data'][0]['id']

# Update the configuration to use direct disk
update_config_data = {
    'kernel': 'linode/direct-disk',
}

response = requests.put(f'https://api.linode.com/v4/linode/instances/{linode_id}/configs/{config_id}', headers=headers, json=update_config_data)

if response.status_code == 200:
    print("Linode VM configuration updated to use direct disk.")
else:
    print(f"Failed to update configuration: {response.json()}")

# Wait for 2 seconds
time.sleep(2)

# Boot the Linode instance
response = requests.post(f'https://api.linode.com/v4/linode/instances/{linode_id}/boot', headers=headers)

if response.status_code == 200:
    print("Linode VM booted successfully.")
else:
    print(f"Failed to boot Linode VM: {response.json()}")